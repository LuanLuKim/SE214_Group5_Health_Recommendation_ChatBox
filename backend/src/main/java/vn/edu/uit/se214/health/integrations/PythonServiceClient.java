package vn.edu.uit.se214.health.integrations;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import vn.edu.uit.se214.health.shared.AppProperties;

/**
 * REST/JSON client for a companion Python service on the internal network. Every call carries
 * the shared service key in {@code X-Service-Key}. Send only the minimum data needed: never
 * names, emails or whole histories (M-11).
 */
public class PythonServiceClient {
  public static final String KEY_HEADER = "X-Service-Key";

  public interface StreamConsumer {
    void onLine(String line) throws Exception;
  }

  private final RestClient http;
  private final boolean configured;

  /** {@code builder} should already carry the request factory (timeouts) for this service. */
  public PythonServiceClient(AppProperties.PythonService service, RestClient.Builder builder) {
    this.configured = service != null && service.configured();
    this.http =
        builder
            .baseUrl(configured ? service.baseUrl() : "http://127.0.0.1")
            .defaultHeader(KEY_HEADER, configured ? service.key() : "")
            .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
            .build();
  }

  public boolean configured() {
    return configured;
  }

  /** POST JSON and map the response. Callers turn failures into a safe, user-facing message. */
  public <T> T post(String path, Object body, Class<T> type) {
    requireConfigured();
    return http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(type);
  }

  public <T> T get(String path, Class<T> type) {
    requireConfigured();
    return http.get().uri(path).retrieve().body(type);
  }

  public void postStream(String path, Object body, StreamConsumer consumer) {
    requireConfigured();
    http.post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .body(body)
        .exchange(
            (request, response) -> {
              try (var reader =
                  new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  try {
                    consumer.onLine(line);
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                }
              }
              return null;
            });
  }

  private void requireConfigured() {
    if (!configured) throw new IllegalStateException("SERVICE_NOT_CONFIGURED");
  }
}
