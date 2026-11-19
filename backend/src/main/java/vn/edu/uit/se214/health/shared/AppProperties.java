package vn.edu.uit.se214.health.shared;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Values under {@code app.*} in application.yml. Secrets come from environment variables only. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    String dataMode,
    String webOrigin,
    long maxBodyBytes,
    int rateLimitPerMinute,
    boolean seedSynthetic,
    String databaseDir,
    PythonService aiService,
    PythonService statsService) {

  public boolean isMockData() {
    return !"postgres".equalsIgnoreCase(dataMode);
  }

  /** A companion Python service. Only Spring Boot calls it, with a shared service key. */
  public record PythonService(String baseUrl, String key, int timeoutMs) {
    public boolean configured() {
      return baseUrl != null && !baseUrl.isBlank() && key != null && !key.isBlank();
    }
  }
}
