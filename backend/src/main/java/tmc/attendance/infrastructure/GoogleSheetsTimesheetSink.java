package tmc.attendance.infrastructure;

import java.io.IOException;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import tmc.attendance.application.TimesheetSink;
import tmc.attendance.domain.TimesheetRow;

/** Placeholder only. The connector needs approved data handling before it can transmit records. */
public record GoogleSheetsTimesheetSink(String spreadsheetId, Path serviceAccount) implements TimesheetSink {
  @Override
  public void publish(List<TimesheetRow> rows, YearMonth period) throws IOException {
    throw new IOException("Google Sheets integration unavailable pending TMC approval");
  }
}
