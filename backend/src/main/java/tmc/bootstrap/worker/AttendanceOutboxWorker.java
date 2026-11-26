package tmc.bootstrap.worker;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tmc.access.infrastructure.persistence.JdbcAttemptStore;
import tmc.attendance.application.AttendanceRecorder;
import tmc.attendance.infrastructure.TimesheetService;
import tmc.bootstrap.mapping.AccessToAttendance;

@Component
public class AttendanceOutboxWorker {
  private final JdbcAttemptStore attempts;
  private final AttendanceRecorder recorder;
  private final TimesheetService timesheets;

  public AttendanceOutboxWorker(JdbcAttemptStore attempts, AttendanceRecorder recorder, TimesheetService timesheets) {
    this.attempts = attempts;
    this.recorder = recorder;
    this.timesheets = timesheets;
  }

  @Scheduled(fixedDelay = 500)
  public void dispatch() {
    for (var attempt : attempts.pendingAttendance(100)) {
      try {
        recorder.accept(AccessToAttendance.from(attempt));
        timesheets.project(attempt.employeeId(), attempt.scannedAt());
        attempts.attendanceDone(attempt);
      } catch (RuntimeException retryNextTick) {
      }
    }
  }
}
