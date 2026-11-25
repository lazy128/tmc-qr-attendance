package tmc.access.domain.port;

import tmc.access.domain.AccessAttempt;

public record IdempotencyEntry(String requestFingerprint, boolean inProgress, AccessAttempt result) {}
