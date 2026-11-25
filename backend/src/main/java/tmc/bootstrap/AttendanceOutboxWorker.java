package tmc.bootstrap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tmc.access.infrastructure.JdbcAttemptStore;
import tmc.attendance.application.AttendanceRecorder;
import tmc.bootstrap.mapping.AccessToAttendance;

@Component
public class AttendanceOutboxWorker {
  private final JdbcAttemptStore attempts;
  private final AttendanceRecorder recorder;

  public AttendanceOutboxWorker(JdbcAttemptStore attempts, AttendanceRecorder recorder) {
    this.attempts = attempts;
    this.recorder = recorder;
  }

  @Scheduled(fixedDelay = 500)
  public void dispatch() {
    for (var attempt : attempts.pendingAttendance(100)) {
      try {
        recorder.accept(AccessToAttendance.from(attempt));
        attempts.attendanceDone(attempt);
      } catch (RuntimeException retryNextTick) {
      }
    }
  }
}
