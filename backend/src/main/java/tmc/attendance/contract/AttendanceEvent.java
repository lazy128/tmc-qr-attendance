package tmc.attendance.contract;

import java.time.Instant;

public record AttendanceEvent(
    String eventId,
    String employeeId,
    String siteId,
    String stationId,
    String direction,
    Instant scannedAt,
    String command,
    String passage,
    String evidence,
    boolean suspicious
) {}
