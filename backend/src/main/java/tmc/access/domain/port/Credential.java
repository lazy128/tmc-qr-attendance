package tmc.access.domain.port;

public record Credential(
    String id,
    String employeeId,
    int version,
    boolean active,
    String deviceHash
) {}
