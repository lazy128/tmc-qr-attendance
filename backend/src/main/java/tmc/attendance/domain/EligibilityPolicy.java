package tmc.attendance.domain;

import tmc.attendance.contract.AttendanceEvent;

public interface EligibilityPolicy {
  boolean eligible(AttendanceEvent event);

  boolean requiresHrReview(AttendanceEvent event);
}
