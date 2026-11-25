package tmc.attendance.application;

import tmc.attendance.contract.AttendanceEvent;
import tmc.attendance.domain.AttendanceRecord;
import tmc.attendance.domain.EligibilityPolicy;

public final class RecordAttendance implements AttendanceRecorder {
  private final AttendanceRecordStore store;
  private final EligibilityPolicy policy;

  public RecordAttendance(AttendanceRecordStore store, EligibilityPolicy policy) {
    this.store = store;
    this.policy = policy;
  }

  @Override
  public void accept(AttendanceEvent event) {
    store.insertIfAbsent(new AttendanceRecord(
        event.eventId(),
        event.employeeId(),
        event.siteId(),
        event.stationId(),
        event.direction(),
        event.scannedAt(),
        event.command(),
        event.passage(),
        event.evidence(),
        policy.eligible(event),
        policy.requiresHrReview(event),
        event.suspicious()
    ));
  }
}
