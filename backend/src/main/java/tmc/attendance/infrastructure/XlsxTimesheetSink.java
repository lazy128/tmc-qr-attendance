package tmc.attendance.infrastructure;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tmc.attendance.application.TimesheetSink;
import tmc.attendance.domain.TimesheetRow;
import tmc.shared.WorkplaceSites;

public record XlsxTimesheetSink(Path directory, ZoneId zone) implements TimesheetSink {
  public Path file(YearMonth month) { return directory.resolve("timesheet-" + month + ".xlsx"); }

  @Override
  public void publish(List<TimesheetRow> rows, YearMonth period) throws IOException {
    Files.createDirectories(directory);
    var temp = Files.createTempFile(directory, "timesheet-", ".tmp");
    try {
      try (var stream = Files.newOutputStream(temp)) { write(rows, stream); }
      try {
        Files.move(temp, file(period), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unavailable) {
        Files.move(temp, file(period), StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  public byte[] export(List<TimesheetRow> rows) throws IOException {
    var stream = new ByteArrayOutputStream();
    write(rows, stream);
    return stream.toByteArray();
  }

  private void write(List<TimesheetRow> rows, OutputStream stream) throws IOException {
    try (var workbook = new XSSFWorkbook()) {
      var header = workbook.createCellStyle();
      var font = workbook.createFont();
      font.setBold(true);
      header.setFont(font);
      var date = workbook.createCellStyle();
      date.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
      var dateTime = workbook.createCellStyle();
      dateTime.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy hh:mm"));
      var duration = workbook.createCellStyle();
      duration.setDataFormat(workbook.createDataFormat().getFormat("[h]:mm"));
      var totals = workbook.createSheet("Tổng hợp");
      var detail = workbook.createSheet("Chi tiết ngày");
      var review = workbook.createSheet("Cần HR xem");
      headers(totals, header, "Mã NV", "Họ và tên", "Ngày có giờ công", "Tổng giờ", "Ngày cần xem", "Nhà máy");
      for (var sheet : List.of(detail, review)) {
        headers(sheet, header, "Mã NV", "Họ và tên", "Nhà máy", "Ngày công", "Giờ vào", "Giờ ra", "Tổng giờ", "Trạng thái");
      }
      var byEmployee = new TreeMap<EmployeeAtSite, Total>(Comparator.comparing(EmployeeAtSite::site)
          .thenComparing(EmployeeAtSite::id));
      int nextDetail = 1;
      int nextReview = 1;
      for (var item : rows) {
        var key = new EmployeeAtSite(item.employeeId(), WorkplaceSites.name(item.siteId()));
        byEmployee.computeIfAbsent(key, ignored -> new Total(item.employeeName())).add(item);
        detail(detail.createRow(nextDetail++), item, date, dateTime, duration);
        if (item.reviewFlag()) detail(review.createRow(nextReview++), item, date, dateTime, duration);
      }
      int nextTotal = 1;
      for (var item : byEmployee.entrySet()) {
        var row = totals.createRow(nextTotal++);
        row.createCell(0).setCellValue(item.getKey().id());
        row.createCell(1).setCellValue(item.getValue().name);
        row.createCell(2).setCellValue(item.getValue().days);
        writeDuration(row, 3, item.getValue().minutes, duration);
        row.createCell(4).setCellValue(item.getValue().reviewDays);
        row.createCell(5).setCellValue(item.getKey().site());
      }
      for (var sheet : List.of(totals, detail, review)) {
        int columns = sheet.getRow(0).getLastCellNum();
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, sheet.getLastRowNum(), 0, columns - 1));
      }
      workbook.write(stream);
    }
  }

  private static void headers(Sheet sheet, CellStyle style, String... labels) {
    var row = sheet.createRow(0);
    for (int i = 0; i < labels.length; i++) {
      var cell = row.createCell(i);
      cell.setCellValue(labels[i]);
      cell.setCellStyle(style);
      sheet.setColumnWidth(i, (i == 1 ? 26 : 22) * 256);
    }
    sheet.createFreezePane(0, 1);
  }

  private void detail(Row row, TimesheetRow value, CellStyle date, CellStyle dateTime, CellStyle duration) {
    row.createCell(0).setCellValue(value.employeeId());
    row.createCell(1).setCellValue(value.employeeName());
    row.createCell(2).setCellValue(WorkplaceSites.name(value.siteId()));
    row.createCell(3).setCellValue(value.workDate());
    row.getCell(3).setCellStyle(date);
    if (value.firstIn() != null) row.createCell(4).setCellValue(LocalDateTime.ofInstant(value.firstIn(), zone));
    if (value.lastOut() != null) row.createCell(5).setCellValue(LocalDateTime.ofInstant(value.lastOut(), zone));
    if (row.getCell(4) != null) row.getCell(4).setCellStyle(dateTime);
    if (row.getCell(5) != null) row.getCell(5).setCellStyle(dateTime);
    writeDuration(row, 6, value.workedMinutes(), duration);
    row.createCell(7).setCellValue(value.status().label());
  }

  private static void writeDuration(Row row, int column, long minutes, CellStyle style) {
    var cell = row.createCell(column);
    cell.setCellValue(minutes / 1440.0);
    cell.setCellStyle(style);
  }

  private static final class Total {
    private final String name;
    private int days;
    private long minutes;
    private int reviewDays;

    private Total(String name) { this.name = name; }
    private void add(TimesheetRow row) {
      if (row.workedMinutes() > 0) days++;
      minutes += row.workedMinutes();
      if (row.reviewFlag()) reviewDays++;
    }
  }

  private record EmployeeAtSite(String id, String site) {}
}
