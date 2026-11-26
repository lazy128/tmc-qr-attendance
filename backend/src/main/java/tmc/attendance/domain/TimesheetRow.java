package tmc.attendance.domain;

import java.time.Instant;
import java.time.LocalDate;

public record TimesheetRow(String employeeId, String employeeName, LocalDate workDate, String siteId,
    Instant firstIn, Instant lastOut, long workedMinutes, Status status, boolean reviewFlag) {
  public enum Status {
    COMPLETE("ĐỦ"), MISSING_EXIT("THIẾU GIỜ RA"), DUPLICATE("TRÙNG LƯỢT"), REVIEW("CẦN HR XEM");

    private final String label;
    Status(String label) { this.label = label; }
    public String label() { return label; }
  }
}
