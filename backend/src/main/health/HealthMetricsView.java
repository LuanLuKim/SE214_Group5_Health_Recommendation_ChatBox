package health;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * UC-05 response for a week or month window. {@code points} holds only days that have a record.
 * {@code insightsStatus} is OK (summary and insights come from services/stats), NO_DATA (nothing
 * recorded in the window, no trend comment, A4) or UNAVAILABLE (statistics could not be made but
 * the points are still returned so the chart works, A5).
 */
public record HealthMetricsView(
    String period,
    LocalDate from,
    LocalDate to,
    List<HealthMetricPoint> points,
    Map<String, String> units,
    String insightsStatus,
    JsonNode summary,
    JsonNode insights,
    String message) {}
