package tmc.access.domain;

public record SiteId(String value) {
  public SiteId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("siteId required");
    }
  }
}
