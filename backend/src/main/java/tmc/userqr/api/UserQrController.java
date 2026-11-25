package tmc.userqr.api;

import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tmc.userqr.application.IssueQrToken;
import tmc.userqr.application.RevokeCredential;
import tmc.shared.audit.AuditWriter;
import tmc.shared.clock.ClockProvider;
import tmc.shared.web.CorrelationIdFilter;

@RestController
public class UserQrController {
  private final IssueQrToken issue;
  private final RevokeCredential revoke;
  private final AuditWriter audit;
  private final ClockProvider clock;

  public UserQrController(IssueQrToken issue, RevokeCredential revoke, AuditWriter audit, ClockProvider clock) {
    this.issue = issue;
    this.revoke = revoke;
    this.audit = audit;
    this.clock = clock;
  }

  @PostMapping("/qr/v1/tokens")
  public Map<String, String> issue(@RequestBody IssueRequest request) {
    String token = issue.issue(request.credentialId(), request.deviceHash());
    audit.append("demo-actor", "QR_ISSUE", request.credentialId(), "SUCCESS", CorrelationIdFilter.currentId(), clock.now());
    return Map.of("token", token);
  }

  @PostMapping("/qr/v1/credentials/{id}/revoke")
  @Transactional
  public Map<String, String> revoke(@PathVariable String id) {
    revoke.revoke(id, "demo-actor", CorrelationIdFilter.currentId());
    return Map.of("status", "REVOKED");
  }

  public record IssueRequest(String credentialId, String deviceHash) {}
}
