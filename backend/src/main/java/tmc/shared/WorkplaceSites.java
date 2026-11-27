package tmc.shared;

import java.util.List;

public final class WorkplaceSites {
  public record Site(String id, String name, String entryStation) {
    public String exitStation() { return entryStation + "-OUT"; }
  }

  public static final List<Site> ALL = List.of(
      new Site("SITE-BD", "Bình Dương", "BD-G01"),
      new Site("SITE-LA", "Long An", "LA-G01"));

  private WorkplaceSites() {}

  public static String name(String id) {
    return ALL.stream().filter(site -> site.id().equals(StaffDirectory.siteFor(id)))
        .map(Site::name).findFirst().orElse(id);
  }
}
