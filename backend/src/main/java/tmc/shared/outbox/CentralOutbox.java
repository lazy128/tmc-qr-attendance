package tmc.shared.outbox;

import java.util.List;

public interface CentralOutbox {
  List<String> pendingCentral(int limit);

  void centralDone(String payload);
}
