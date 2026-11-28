package tmc.access.infrastructure.turnstile;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.StationId;
import tmc.access.domain.port.GateCommandSender;
import tmc.access.domain.port.GateResult;

public final class TimeoutGateCommandSender implements GateCommandSender, AutoCloseable {
  private final GateCommandSender delegate;
  private final ThreadPoolExecutor executor;
  private final long timeoutMillis;
  private final int failureLimit;
  private final long resetNanos;
  private final LongSupplier time;
  private final ConcurrentHashMap<StationId, Circuit> circuits = new ConcurrentHashMap<>();

  public TimeoutGateCommandSender(GateCommandSender delegate, long timeoutMillis) {
    this(delegate, timeoutMillis, 3, Duration.ofSeconds(15));
  }

  public TimeoutGateCommandSender(GateCommandSender delegate, long timeoutMillis, int failures, Duration reset) {
    this(delegate, timeoutMillis, failures, reset, System::nanoTime);
  }

  TimeoutGateCommandSender(GateCommandSender delegate, long timeoutMillis, int failures, Duration reset, LongSupplier time) {
    if (timeoutMillis < 1 || failures < 1 || reset.isZero() || reset.isNegative()) {
      throw new IllegalArgumentException("Turnstile timeout and circuit settings must be positive");
    }
    this.delegate = delegate;
    this.timeoutMillis = timeoutMillis;
    this.failureLimit = failures;
    this.resetNanos = reset.toNanos();
    this.time = time;
    this.executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(64), runnable -> {
          Thread thread = new Thread(runnable, "gate-command");
          thread.setDaemon(true);
          return thread;
        });
  }

  @Override
  public GateResult send(String commandId, AccessRequest request) {
    Circuit circuit = circuits.computeIfAbsent(request.stationId(), ignored -> new Circuit());
    var permit = circuit.acquire(time.getAsLong());
    if (permit == null) return GateResult.FAILED;
    GateResult result = dispatch(commandId, request);
    circuit.complete(permit, result, time.getAsLong());
    return result;
  }

  private GateResult dispatch(String commandId, AccessRequest request) {
    Future<GateResult> future;
    try {
      future = executor.submit(() -> delegate.send(commandId, request));
    } catch (RejectedExecutionException saturated) {
      return GateResult.FAILED;
    }
    try {
      return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      return cancel(future);
    } catch (InterruptedException interrupted) {
      var result = cancel(future);
      Thread.currentThread().interrupt();
      return result;
    } catch (ExecutionException failed) {
      return GateResult.TIMEOUT;
    }
  }

  private GateResult cancel(Future<GateResult> future) {
    boolean stillQueued = executor.remove((Runnable) future);
    future.cancel(!stillQueued);
    return stillQueued ? GateResult.FAILED : GateResult.TIMEOUT;
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }

  private record Permit(long generation, boolean probe) {}

  private final class Circuit {
    private long generation;
    private int failures;
    private long until;
    private boolean open;
    private boolean probing;

    synchronized Permit acquire(long now) {
      if (!open) return new Permit(generation, false);
      if (probing || now - until < 0) return null;
      probing = true;
      return new Permit(generation, true);
    }

    synchronized void complete(Permit permit, GateResult result, long now) {
      if (permit.generation() != generation) return;
      if (permit.probe()) {
        probing = false;
        if (result == GateResult.ACKED) {
          failures = 0;
          open = false;
          generation++;
        } else {
          reopen(now);
        }
      } else if (result == GateResult.ACKED) {
        failures = 0;
      } else if (++failures >= failureLimit) {
        reopen(now);
      }
    }

    private void reopen(long now) {
      open = true;
      until = now + resetNanos;
      generation++;
    }
  }
}
