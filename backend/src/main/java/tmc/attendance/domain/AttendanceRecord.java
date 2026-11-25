package tmc.attendance.domain;

import java.time.Instant;

public record AttendanceRecord(
    String eventId,
    String employeeId,
    String siteId,
    String stationId,
    String direction,
    Instant scannedAt,
    String command,
    String passage,
    String evidence,
    boolean eligible,
    boolean hrReview,
    boolean suspicious
) {}
