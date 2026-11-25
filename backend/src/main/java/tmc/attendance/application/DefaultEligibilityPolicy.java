package tmc.attendance.application;

import tmc.attendance.contract.AttendanceEvent;
import tmc.attendance.domain.EligibilityPolicy;

public final class DefaultEligibilityPolicy implements EligibilityPolicy {
  @Override
  public boolean eligible(AttendanceEvent event) {
    return "ACKED".equals(event.command());
  }

  @Override
  public boolean requiresHrReview(AttendanceEvent event) {
    return "ACKED".equals(event.command()) && "ACK_ONLY".equals(event.evidence());
  }
}
