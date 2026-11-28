package tmc.access.domain;

public final class AccessStates {
  private AccessStates() {}

  public enum Decision {
    ALLOW,
    DENY
  }

  public enum Command {
    NOT_SENT,
    SENT,
    ACKED,
    FAILED,
    UNKNOWN,
    EXPIRED
  }

  public enum Passage {
    NOT_AVAILABLE,
    UNCONFIRMED,
    CONFIRMED
  }

  public enum Channel {
    GATE_SCAN,
    STATION_QR,
    PERSONAL_SELF;

    public static final String PERSONAL_AUDIENCE = "tmc-personal-checkin";
  }

  public enum Evidence {
    ACK_ONLY,
    PASSAGE_CONFIRMED,
    SELF_REPORTED,
    MANUAL_PASSAGE,
    MANUAL_NO_PASSAGE
  }

  public enum Reason {
    TOKEN_EXPIRED,
    NOT_YET_VALID,
    CLOCK_SKEW,
    INVALID_TOKEN,
    REPLAY,
    REVOKED,
    CVER_MISMATCH,
    DEVICE_MISMATCH,
    ANTI_PASSBACK,
    COMMAND_PENDING,
    PERSONAL_SELF_LIMIT,
    SHIFT_COMPLETED,
    STATION_SCOPE,
    ZONE_DENIED,
    OUTSIDE_SHIFT,
    EMPLOYEE_INACTIVE,
    POLICY_DENIED,
    PERSIST_FAILED,
    STATION_AUTH_FAILED,
    SNAPSHOT_STALE,
    REQUEST_IN_PROGRESS
  }
}
