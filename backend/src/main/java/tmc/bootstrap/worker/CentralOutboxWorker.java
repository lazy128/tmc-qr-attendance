package tmc.bootstrap.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import tmc.shared.outbox.CentralOutbox;

/** Delivers Edge events at least once; Central deduplicates by eventId. */
@Component
@ConditionalOnProperty(name = "tmc.central.enabled", havingValue = "true")
public class CentralOutboxWorker {
  private final CentralOutbox outbox;
  private final RestTemplate http;
  private final String centralUrl;
  private final String syncToken;

  public CentralOutboxWorker(
      CentralOutbox outbox,
      RestTemplate http,
      @Value("${tmc.central.url}") String centralUrl,
      @Value("${TMC_EDGE_SYNC_TOKEN:}") String syncToken
  ) {
    this.outbox = outbox;
    this.http = http;
    this.centralUrl = centralUrl.replaceAll("/$", "");
    this.syncToken = syncToken;
  }

  @Scheduled(fixedDelay = 1000)
  public void dispatch() {
    for (String payload : outbox.pendingCentral(50)) {
      try {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!syncToken.isBlank()) headers.setBearerAuth(syncToken);
        HttpEntity<String> request = new HttpEntity<>("[" + payload + "]", headers);
        http.postForEntity(centralUrl + "/edge/v1/sync", request, String.class);
        outbox.centralDone(payload);
      } catch (RuntimeException unavailable) {
        // Keep the row pending; a later tick retries without blocking gate scans.
      }
    }
  }
}
