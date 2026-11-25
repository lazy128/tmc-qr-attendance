package tmc.access.domain;

public final class ReplayDetectedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ReplayDetectedException() {
    super("QR token has already been used at this site");
  }
}
