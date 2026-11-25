package tmc.access.application;

import java.time.Duration;
import java.util.List;
import tmc.access.application.port.StationAuthenticator;
import tmc.access.application.port.StationScope;
import tmc.access.application.step.AntiPassbackStep;
import tmc.access.application.step.CredentialStatusAndVersionStep;
import tmc.access.application.step.GateCommandStep;
import tmc.access.application.step.OutcomeRecordStep;
import tmc.access.application.step.PersistAndConsumeStep;
import tmc.access.application.step.PolicyStep;
import tmc.access.application.step.ReplayCheckStep;
import tmc.access.application.step.StationAuthStep;
import tmc.access.application.step.StationScopeStep;
import tmc.access.application.step.TokenSignatureAndTimeStep;
import tmc.access.domain.port.AttemptStore;
import tmc.access.domain.port.CredentialReader;
import tmc.access.domain.port.GateCommandSender;
import tmc.access.domain.port.PolicyEvaluator;
import tmc.access.domain.port.PresenceTracker;
import tmc.access.domain.port.SnapshotProvider;
import tmc.access.domain.port.TokenValidator;
import tmc.access.domain.port.UsedTokenStore;
import tmc.shared.clock.ClockProvider;

public final class VerificationPipeline {
  private VerificationPipeline() {}

  public static List<VerificationStep> create(
      StationAuthenticator stationAuth,
      StationScope stationScope,
      TokenValidator validator,
      CredentialReader credentials,
      SnapshotProvider snapshot,
      PolicyEvaluator policy,
      PresenceTracker presence,
      UsedTokenStore usedTokens,
      AttemptStore attempts,
      GateCommandSender gate,
      ClockProvider clock,
      boolean sensorConfigured,
      Duration ttlSkew,
      Duration snapshotMaxAge,
      String expectedIssuer,
      String expectedAudience
  ) {
    return List.of(
        new StationAuthStep(stationAuth),
        new StationScopeStep(stationScope),
        new TokenSignatureAndTimeStep(validator, clock, ttlSkew, expectedIssuer, expectedAudience),
        new CredentialStatusAndVersionStep(credentials, snapshot),
        new PolicyStep(policy, snapshot, clock, snapshotMaxAge),
        new ReplayCheckStep(usedTokens),
        new AntiPassbackStep(presence),
        new PersistAndConsumeStep(attempts, clock, sensorConfigured),
        new GateCommandStep(gate),
        new OutcomeRecordStep(attempts)
    );
  }
}
