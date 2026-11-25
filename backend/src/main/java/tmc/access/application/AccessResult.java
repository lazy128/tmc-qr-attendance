package tmc.access.application;

import java.util.List;
import tmc.access.domain.AccessAttempt;

public record AccessResult(AccessAttempt attempt, List<String> steps, boolean inProgress) {}
