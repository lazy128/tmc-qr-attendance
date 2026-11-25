package tmc.attendance.infrastructure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tmc.attendance.application.AttendanceRecordStore;
import tmc.attendance.domain.AttendanceRecord;

public final class InMemoryAttendanceStore implements AttendanceRecordStore {
  private final Map<String, AttendanceRecord> rows = new HashMap<>();

  @Override
  public synchronized boolean insertIfAbsent(AttendanceRecord record) {
    var existing = rows.putIfAbsent(record.eventId(), record);
    if (existing != null && "CONFIRMED".equals(record.passage())) rows.put(record.eventId(), record);
    return existing == null;
  }

  @Override
  public synchronized Optional<AttendanceRecord> find(String eventId) {
    return Optional.ofNullable(rows.get(eventId));
  }

  @Override
  public synchronized List<AttendanceRecord> all() {
    return List.copyOf(rows.values());
  }
}
