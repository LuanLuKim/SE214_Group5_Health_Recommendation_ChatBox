package vn.edu.uit.se214.health.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.web.client.RestClientException;
import vn.edu.uit.se214.health.accounts.api.ConsentQuery;
import vn.edu.uit.se214.health.integrations.PythonServiceClient;
import vn.edu.uit.se214.health.shared.web.ApiException;

/**
 * UC-05 Ghi nhận và xem thống kê chỉ số. Reading and saving need the user's consent to health
 * data processing; deleting does not, so a user who withdrew consent can still remove data.
 * Aggregation and the threshold comments live in services/stats (Django); this class reads the
 * user's own days from the database and sends only dates, weights and sleep hours to it.
 */
public class HealthMetricsService {
  static final int WEIGHT_MIN = 20;
  static final int WEIGHT_MAX = 300;
  static final int SLEEP_MIN = 0;
  static final int SLEEP_MAX = 24;
  /** A week is the 7 days ending on the chosen day, a month the 30 days ending on it. */
  static final int WEEK_DAYS = 7;
  static final int MONTH_DAYS = 30;
  static final Map<String, String> UNITS = Map.of("weightKg", "kg", "sleepHours", "giờ");

  private final HealthMetricsRepository repository;
  private final ConsentQuery consents;
  private final PythonServiceClient stats;
  private final Clock clock;

  public HealthMetricsService(
      HealthMetricsRepository repository, ConsentQuery consents, PythonServiceClient stats, Clock clock) {
    this.repository = repository;
    this.consents = consents;
    this.stats = stats;
    this.clock = clock;
  }

  /** GET /metrics?period=week|month&to=yyyy-MM-dd (both optional: week, ending today). */
  public HealthMetricsView range(UUID userId, String period, String to) {
    requireConsent(userId);
    List<String> errors = new ArrayList<>();
    String chosen = period == null || period.isBlank() ? "week" : period;
    int days = "week".equals(chosen) ? WEEK_DAYS : "month".equals(chosen) ? MONTH_DAYS : 0;
    if (days == 0) errors.add("Khoảng xem (period) phải là week hoặc month.");
    LocalDate end = LocalDate.now(clock);
    if (to != null && !to.isBlank()) {
      LocalDate parsed = parseDate(to);
      if (parsed == null || parsed.isAfter(end)) {
        errors.add("Ngày kết thúc (to) phải theo định dạng yyyy-MM-dd và không ở tương lai.");
      } else {
        end = parsed;
      }
    }
    if (!errors.isEmpty()) throw HealthErrors.metricsInvalid(String.join(" ", errors));

    LocalDate from = end.minusDays(days - 1L);
    List<HealthMetricPoint> points;
    try {
      points = repository.range(userId, from, end);
    } catch (DataAccessException e) {
      throw HealthErrors.metricsReadFailed();
    }
    if (points.isEmpty()) {
      return view(
          chosen, from, end, points, "NO_DATA", null, JsonNodeFactory.instance.arrayNode(),
          "Chưa có dữ liệu trong khoảng này.");
    }
    try {
      JsonNode response = stats.post("/v1/metrics/trends", payload(chosen, from, end, points), JsonNode.class);
      JsonNode data = response == null ? null : response.get("data");
      if (data == null || !data.hasNonNull("summary") || !data.hasNonNull("insights")) {
        throw new IllegalStateException("STATS_BAD_RESPONSE");
      }
      return view(chosen, from, end, points, "OK", data.get("summary"), data.get("insights"), null);
    } catch (RestClientException | IllegalStateException e) {
      // A5: only a stats outage (Django down, timeout, 4xx/5xx, SERVICE_NOT_CONFIGURED, an unusable
      // answer) becomes UNAVAILABLE; a programming error must surface, not hide behind it.
      // The person can retry. Nothing about the data is logged.
      return view(
          chosen, from, end, points, "UNAVAILABLE", null, JsonNodeFactory.instance.arrayNode(),
          "Chưa tạo được nhận xét lúc này. Biểu đồ vẫn hiển thị, bạn có thể thử lại sau.");
    }
  }

  /** PUT /metrics/{date}. Body {weightKg?, sleepHours?}: at least one; an omitted one keeps its value. */
  public HealthMetricPoint save(UUID userId, String date, JsonNode body) {
    requireConsent(userId);
    List<String> errors = new ArrayList<>();
    LocalDate day = parseDate(date);
    if (day == null || day.isAfter(LocalDate.now(clock))) {
      errors.add("Ngày phải theo định dạng yyyy-MM-dd (ví dụ 2026-11-21) và không ở tương lai.");
    }
    BigDecimal weight = null;
    BigDecimal sleep = null;
    if (body == null || !body.isObject()) {
      errors.add("Dữ liệu phải là một đối tượng JSON gồm weightKg và/hoặc sleepHours.");
    } else {
      weight = number(body, "weightKg", WEIGHT_MIN, WEIGHT_MAX, "Cân nặng (weightKg) phải là số từ 20 đến 300 kg.", errors);
      sleep = number(body, "sleepHours", SLEEP_MIN, SLEEP_MAX, "Số giờ ngủ (sleepHours) phải là số từ 0 đến 24 giờ.", errors);
      if (errors.isEmpty() && weight == null && sleep == null) {
        errors.add("Cần ít nhất một chỉ số: weightKg hoặc sleepHours.");
      }
    }
    if (!errors.isEmpty()) throw HealthErrors.metricsInvalid(String.join(" ", errors));
    try {
      return repository.upsert(userId, day, weight, sleep);
    } catch (DataAccessException e) {
      throw HealthErrors.metricsSaveFailed();
    }
  }

  /** DELETE /metrics/{date}: removes the day's record; deleting a missing day is not an error. */
  public void delete(UUID userId, String date) {
    LocalDate day = parseDate(date);
    if (day == null) {
      throw HealthErrors.metricsInvalid("Ngày phải theo định dạng yyyy-MM-dd (ví dụ 2026-11-21).");
    }
    try {
      repository.delete(userId, day);
    } catch (DataAccessException e) {
      throw HealthErrors.metricsDeleteFailed();
    }
  }

  private void requireConsent(UUID userId) {
    if (!consents.healthDataProcessingAccepted(userId)) throw ApiException.healthConsentRequired();
  }

  private static LocalDate parseDate(String text) {
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException | NullPointerException e) {
      return null;
    }
  }

  /** Null when the field is absent or null; an error when it is not a number or out of range. */
  private static BigDecimal number(JsonNode body, String field, int min, int max, String error, List<String> errors) {
    JsonNode node = body.get(field);
    if (node == null || node.isNull()) return null;
    if (!node.isNumber()) {
      errors.add(error);
      return null;
    }
    BigDecimal value = node.decimalValue();
    if (value.compareTo(BigDecimal.valueOf(min)) < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0) {
      errors.add(error);
      return null;
    }
    return value;
  }

  /** Minimum needed by services/stats: no user id, only the window and the day values. */
  private static Map<String, Object> payload(String period, LocalDate from, LocalDate to, List<HealthMetricPoint> points) {
    List<Map<String, Object>> items = new ArrayList<>();
    for (HealthMetricPoint p : points) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("date", p.date().toString());
      item.put("weightKg", p.weightKg());
      item.put("sleepHours", p.sleepHours());
      items.add(item);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("period", period);
    body.put("from", from.toString());
    body.put("to", to.toString());
    body.put("points", items);
    return body;
  }

  private static HealthMetricsView view(
      String period, LocalDate from, LocalDate to, List<HealthMetricPoint> points,
      String status, JsonNode summary, JsonNode insights, String message) {
    return new HealthMetricsView(period, from, to, points, UNITS, status, summary, insights, message);
  }
}
