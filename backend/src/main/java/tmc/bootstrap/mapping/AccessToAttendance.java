package tmc.bootstrap.mapping;

import tmc.access.domain.AccessAttempt;
import tmc.attendance.contract.AttendanceEvent;

public final class AccessToAttendance {
  private AccessToAttendance() {}

  public static AttendanceEvent from(AccessAttempt attempt) {
    return new AttendanceEvent(
        attempt.eventId().value(),
        attempt.employeeId(),
        attempt.siteId().value(),
        attempt.stationId().value(),
        attempt.direction().name(),
        attempt.scannedAt(),
        attempt.command().name(),
        attempt.passage().name(),
        attempt.duplicateScan() ? "DUPLICATE_SCAN" : attempt.evidence() == null ? null : attempt.evidence().name(),
        attempt.suspicious()
    );
  }
}
