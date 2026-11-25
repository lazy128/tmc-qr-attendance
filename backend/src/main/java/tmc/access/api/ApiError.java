package tmc.access.api;

public record ApiError(String code, String message, String correlationId, boolean retryable) {}
