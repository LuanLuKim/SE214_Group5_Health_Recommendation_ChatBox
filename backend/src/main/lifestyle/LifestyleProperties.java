package lifestyle;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.lifestyle.*}.
 *
 * @param zone the time zone whose local midnight starts a new habit day (UC-13)
 * @param historyLimit most ended plans returned by GET /lifestyle/plans/history
 */
@ConfigurationProperties(prefix = "app.lifestyle")
public record LifestyleProperties(String zone, int historyLimit) {}
