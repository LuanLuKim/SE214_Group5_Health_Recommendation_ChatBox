package vn.edu.uit.se214.health.health;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import vn.edu.uit.se214.health.health.api.UsageEventType;

/**
 * {@code app.health.statistics.*} (UC-19 and SCRUM-51).
 *
 * @param zone time zone whose calendar days the statistics are grouped by
 * @param defaultDays days shown when the request gives no date range (ending today)
 * @param maxDays widest date range one request may ask for
 * @param recordEvents false stops recording usage events; reading statistics still works
 * @param eventRoutes successful responses that count as one usage event each
 */
@ConfigurationProperties(prefix = "app.health.statistics")
public record UsageStatisticsProperties(
    String zone, int defaultDays, int maxDays, boolean recordEvents, List<EventRoute> eventRoutes) {

  public UsageStatisticsProperties {
    eventRoutes = eventRoutes == null ? List.of() : List.copyOf(eventRoutes);
  }

  /** {@code method} and {@code path} match exactly; {@code status} is the success status of that route. */
  public record EventRoute(String method, String path, int status, UsageEventType type) {
    boolean matches(String requestMethod, String requestPath, int responseStatus) {
      return method.equalsIgnoreCase(requestMethod) && path.equals(requestPath) && status == responseStatus;
    }
  }
}
