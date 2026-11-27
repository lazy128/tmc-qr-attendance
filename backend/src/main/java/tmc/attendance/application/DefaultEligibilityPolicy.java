package tmc.attendance.application;

import tmc.attendance.contract.AttendanceEvent;
import tmc.attendance.domain.EligibilityPolicy;

public final class DefaultEligibilityPolicy implements EligibilityPolicy {
  @Override
  public boolean eligible(AttendanceEvent event) {
    return "MANUAL_PASSAGE".equals(event.evidence())
        || ("ACKED".equals(event.command()) && !"SELF_REPORTED".equals(event.evidence()));
  }

  @Override
  public boolean requiresHrReview(AttendanceEvent event) {
    return "SELF_REPORTED".equals(event.evidence())
        || ("ACKED".equals(event.command()) && "ACK_ONLY".equals(event.evidence()));
  }
}
