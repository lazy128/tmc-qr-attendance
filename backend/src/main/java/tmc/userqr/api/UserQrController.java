package tmc.userqr.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tmc.access.api.ApiError;
import tmc.access.application.PermissionPolicy;
import tmc.access.application.ScopeResolver;
import tmc.access.domain.port.CredentialReader;
import tmc.shared.DemoIdentity;
import tmc.userqr.application.IssueQrToken;
import tmc.userqr.application.RevokeCredential;
import tmc.shared.audit.AuditWriter;
import tmc.shared.clock.ClockProvider;
import tmc.shared.web.CorrelationIdFilter;
import tmc.userqr.infrastructure.JdbcDeviceBindings;

@RestController
public class UserQrController {
  private final IssueQrToken issue;
  private final RevokeCredential revoke;
  private final AuditWriter audit;
  private final ClockProvider clock;
  private final DemoIdentity identity;
  private final PermissionPolicy permissions;
  private final ScopeResolver scopes;
  private final CredentialReader credentials;
  private final JdbcDeviceBindings devices;

  public UserQrController(IssueQrToken issue, RevokeCredential revoke, AuditWriter audit, ClockProvider clock,
      DemoIdentity identity, PermissionPolicy permissions, ScopeResolver scopes,
      CredentialReader credentials, JdbcDeviceBindings devices) {
    this.issue = issue;
    this.revoke = revoke;
    this.audit = audit;
    this.clock = clock;
    this.identity = identity;
    this.permissions = permissions;
    this.scopes = scopes;
    this.credentials = credentials;
    this.devices = devices;
  }

  @PostMapping("/qr/v1/tokens")
  @Transactional
  public ResponseEntity<?> issue(@RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Device-Proof", required = false) String proof, @RequestBody IssueRequest request) {
    String actor = authorize(authorization, "qr:issue");
    String id = request.credentialId();
    if (id == null || id.isBlank() || id.length() > 120) throw new IllegalArgumentException("Invalid credential ID");
    var credential = credentials.getCredential(id)
        .filter(value -> scopes.visibleEmployeeIds(actor, "Own").contains(value.employeeId()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Credential outside employee scope"));
    if (!credential.active()) return rejection(HttpStatus.CONFLICT, "REVOKED", "Credential revoked");
    var device = devices.deviceHash(id, proof).filter(hash -> hash.equals(credential.deviceHash()));
    if (device.isEmpty()) {
      audit.append(actor, "QR_ISSUE", id, "DEVICE_MISMATCH", CorrelationIdFilter.currentId(), clock.now());
      return rejection(HttpStatus.FORBIDDEN, "DEVICE_MISMATCH", "Device is not registered for this credential");
    }
    var token = issue.issue(id, device.get());
    audit.append(actor, "QR_ISSUE", id, "SUCCESS", CorrelationIdFilter.currentId(), clock.now());
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(token);
  }

  @PostMapping("/qr/v1/credentials/{id}/devices")
  @Transactional
  public ResponseEntity<Map<String, String>> enroll(@PathVariable String id,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    String actor = authorize(authorization, "qr:enroll-device");
    var credential = credentials.getCredential(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credential not found"));
    if (!credential.active() || credential.deviceHash() == null || credential.deviceHash().isBlank()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Credential cannot enroll a device");
    }
    String proof = devices.enroll(credential);
    audit.append(actor, "QR_DEVICE_ENROLL", id, "SUCCESS", CorrelationIdFilter.currentId(), clock.now());
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("deviceProof", proof));
  }

  @PostMapping("/qr/v1/credentials/{id}/revoke")
  @Transactional
  public Map<String, String> revoke(@PathVariable String id,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    String actor = authorize(authorization, "qr:revoke");
    revoke.revoke(id, actor, CorrelationIdFilter.currentId());
    return Map.of("status", "REVOKED");
  }

  private String authorize(String header, String permission) {
    String user = identity.authenticate(header)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Valid bearer token required"));
    if (!permissions.permits(user, permission)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Permission denied");
    }
    return user;
  }

  private ResponseEntity<ApiError> rejection(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status).body(new ApiError(code, message, CorrelationIdFilter.currentId(), false));
  }

  public record IssueRequest(String credentialId) {}
}
