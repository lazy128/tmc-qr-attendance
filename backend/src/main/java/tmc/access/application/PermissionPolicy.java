package tmc.access.application;

public interface PermissionPolicy {
  boolean permits(String userId, String permission);
}
