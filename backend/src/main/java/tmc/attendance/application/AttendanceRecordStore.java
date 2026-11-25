package tmc.attendance.application;

import java.util.List;
import java.util.Optional;
import tmc.attendance.domain.AttendanceRecord;

public interface AttendanceRecordStore {
  boolean insertIfAbsent(AttendanceRecord record);

  Optional<AttendanceRecord> find(String eventId);

  List<AttendanceRecord> all();
}
