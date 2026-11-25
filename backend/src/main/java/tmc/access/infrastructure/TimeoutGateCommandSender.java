package tmc.access.infrastructure;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tmc.access.domain.AccessRequest;
import tmc.access.domain.port.GateCommandSender;
import tmc.access.domain.port.GateResult;

public final class TimeoutGateCommandSender implements GateCommandSender, AutoCloseable {
  private final GateCommandSender delegate;
  private final ExecutorService executor;
  private final long timeoutMillis;

  public TimeoutGateCommandSender(GateCommandSender delegate, long timeoutMillis) {
    this.delegate = delegate;
    this.timeoutMillis = timeoutMillis;
    this.executor = Executors.newFixedThreadPool(4, runnable -> {
      Thread thread = new Thread(runnable, "gate-command");
      thread.setDaemon(true);
      return thread;
    });
  }

  @Override
  public GateResult send(String commandId, AccessRequest request) {
    Future<GateResult> future = executor.submit(() -> delegate.send(commandId, request));
    try {
      return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      future.cancel(true);
      return GateResult.TIMEOUT;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return GateResult.TIMEOUT;
    } catch (ExecutionException failed) {
      return GateResult.TIMEOUT;
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
