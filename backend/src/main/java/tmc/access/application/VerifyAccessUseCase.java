package tmc.access.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import tmc.access.domain.AccessAttempt;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.AccessStates.Command;
import tmc.access.domain.AccessStates.Decision;
import tmc.access.domain.AccessStates.Passage;
import tmc.access.domain.AccessStates.Reason;
import tmc.access.domain.port.AttemptStore;
import tmc.access.domain.port.IdempotencyStore;
import tmc.shared.clock.ClockProvider;

public final class VerifyAccessUseCase {
  private final List<VerificationStep> steps;
  private final IdempotencyStore idempotency;
  private final AttemptStore attempts;
  private final ClockProvider clock;

  public VerifyAccessUseCase(
      List<VerificationStep> steps,
      IdempotencyStore idempotency,
      AttemptStore attempts,
      ClockProvider clock
  ) {
    this.steps = steps.stream().sorted(Comparator.comparingInt(VerificationStep::order)).toList();
    this.idempotency = idempotency;
    this.attempts = attempts;
    this.clock = clock;
  }

  public AccessResult execute(AccessRequest request) {
    List<String> trace = new ArrayList<>();
    AccessContext context = new AccessContext();
    // Validate the station and site before returning any saved result.
    Reason failure = null;
    for (VerificationStep step : steps) {
      if (step.order() >= 30) break;
      if (failure == null) failure = executeStep(step, request, context, trace);
      else trace.add(step.name() + ":SKIPPED");
    }
    String eventId = request.eventId().value();
    if (failure != null) {
      trace.add("Idempotency:SKIPPED");
    } else {
      try {
        var existing = idempotency.findEvent(eventId);
        if (existing.isPresent()) return replay(existing.get(), request);
        if (!idempotency.reserve(eventId, ScanFingerprint.of(request))) {
          var raced = idempotency.findEvent(eventId);
          if (raced.isPresent()) return replay(raced.get(), request);
          return new AccessResult(null, List.of("IN_PROGRESS"), true);
        }
        context.idempotencyReserved = true;
        trace.add("Idempotency:PASSED");
      } catch (RuntimeException unavailable) {
        failure = Reason.PERSIST_FAILED;
        trace.add("Idempotency:FAILED");
      }
    }
    for (VerificationStep step : steps) {
      if (step.order() < 30) continue;
      if (failure != null || !step.applies(request)) {
        trace.add(step.name() + ":SKIPPED");
        continue;
      }
      failure = executeStep(step, request, context, trace);
    }
    if (failure != null && context.gateInvoked && context.attempt != null) {
      context.attempt = context.attempt.withUnknownOutcome();
      // Reconcile the durable SENT row instead of guessing the controller outcome.
      return new AccessResult(context.attempt, List.copyOf(trace), false);
    }
    if (context.attempt == null) {
      context.attempt = denied(request, failure == null ? Reason.POLICY_DENIED : failure, context);
      if (failure == Reason.PERSIST_FAILED && context.idempotencyReserved) {
        try {
          idempotency.cancel(eventId);
        } catch (RuntimeException unavailable) {
          // The reservation expires if the database cannot be reached.
        }
        trace.add("NO_GATE_COMMAND");
        return new AccessResult(context.attempt, List.copyOf(trace), false);
      }
      try {
        if (context.idempotencyReserved) attempts.persistDenied(context.attempt);
        else attempts.auditRejection(context.attempt);
      } catch (RuntimeException persistFailed) {
        if (context.idempotencyReserved) {
          context.attempt = denied(request, Reason.PERSIST_FAILED);
          try {
            idempotency.cancel(eventId);
          } catch (RuntimeException stillUnavailable) {
            // The reservation lease allows recovery if the database is temporarily unavailable.
          }
        }
      }
      trace.add("NO_GATE_COMMAND");
    }
    return new AccessResult(context.attempt, List.copyOf(trace), false);
  }

  private Reason executeStep(VerificationStep step, AccessRequest request, AccessContext context, List<String> trace) {
    try {
      StepResult result = step.execute(request, context);
      trace.add(step.name() + (result.passed() ? ":PASSED" : ":FAILED"));
      return result.passed() ? null : result.reason();
    } catch (RuntimeException unavailable) {
      trace.add(step.name() + ":FAILED");
      return Reason.PERSIST_FAILED;
    }
  }

  private AccessResult replay(tmc.access.domain.port.IdempotencyEntry entry, AccessRequest request) {
    if (!Objects.equals(ScanFingerprint.of(request), entry.requestFingerprint())) {
      var rejection = denied(request, Reason.REPLAY);
      attempts.auditRejection(rejection);
      return new AccessResult(rejection, List.of("IDEMPOTENCY_CONFLICT", "NO_GATE_COMMAND"), false);
    }
    if (entry.inProgress()) {
      return new AccessResult(entry.result(), List.of("IN_PROGRESS"), true);
    }
    return new AccessResult(entry.result(), List.of("IDEMPOTENT_REPLAY"), false);
  }

  private AccessAttempt denied(AccessRequest request, Reason reason) {
    return denied(request, reason, new AccessContext());
  }

  private AccessAttempt denied(AccessRequest request, Reason reason, AccessContext context) {
    return new AccessAttempt(
        request.eventId(),
        request.correlationId(),
        reason == Reason.REPLAY || reason == Reason.ANTI_PASSBACK || reason == Reason.COMMAND_PENDING
            ? context.employeeId : null,
        request.siteId(),
        request.stationId(),
        request.direction(),
        request.scannedAt(),
        clock.now(),
        null,
        0,
        null,
        Decision.DENY,
        reason,
        Command.NOT_SENT,
        Passage.NOT_AVAILABLE,
        false,
        null,
        false,
        context.attendanceGate,
        request.channel()
    );
  }
}
