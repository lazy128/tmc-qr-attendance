package tmc.attendance.application;

import java.io.IOException;
import java.time.YearMonth;
import java.util.List;
import tmc.attendance.domain.TimesheetRow;

@FunctionalInterface
public interface TimesheetSink {
  void publish(List<TimesheetRow> rows, YearMonth period) throws IOException;
}
