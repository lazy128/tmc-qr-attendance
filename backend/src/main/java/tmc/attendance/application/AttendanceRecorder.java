package tmc.attendance.application;

import tmc.attendance.contract.AttendanceEvent;

public interface AttendanceRecorder {
  void accept(AttendanceEvent event);
}
