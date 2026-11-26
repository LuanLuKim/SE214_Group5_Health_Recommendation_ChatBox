package health;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** SQL for health.daily_metrics (one row per user and day). Values are always bound. */
public class HealthMetricsRepository {
  private final JdbcClient jdbc;

  public HealthMetricsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Days with a record between {@code from} and {@code to} inclusive, oldest first. */
  List<HealthMetricPoint> range(UUID userId, LocalDate from, LocalDate to) {
    return jdbc.sql(
            """
            SELECT recorded_on, weight_kg, sleep_hours FROM health.daily_metrics
            WHERE user_id = ? AND recorded_on BETWEEN ? AND ?
            ORDER BY recorded_on
            """)
        .params(userId, from, to)
        .query((rs, row) -> point(rs))
        .list();
  }

  /**
   * Creates the day or updates it: a metric sent as null keeps the stored value, so saving only
   * the sleep hours does not erase a weight (including the one UC-04 records). Returns the stored
   * row.
   */
  HealthMetricPoint upsert(UUID userId, LocalDate date, BigDecimal weightKg, BigDecimal sleepHours) {
    return jdbc.sql(
            """
            INSERT INTO health.daily_metrics (user_id, recorded_on, weight_kg, sleep_hours)
            VALUES (?, ?, ?::numeric, ?::numeric)
            ON CONFLICT (user_id, recorded_on) DO UPDATE SET
              weight_kg = COALESCE(EXCLUDED.weight_kg, health.daily_metrics.weight_kg),
              sleep_hours = COALESCE(EXCLUDED.sleep_hours, health.daily_metrics.sleep_hours)
            RETURNING recorded_on, weight_kg, sleep_hours
            """)
        .params(userId, date, weightKg, sleepHours)
        .query((rs, row) -> point(rs))
        .single();
  }

  int delete(UUID userId, LocalDate date) {
    return jdbc.sql("DELETE FROM health.daily_metrics WHERE user_id = ? AND recorded_on = ?")
        .params(userId, date)
        .update();
  }

  private static HealthMetricPoint point(ResultSet rs) throws SQLException {
    return new HealthMetricPoint(
        rs.getObject("recorded_on", LocalDate.class),
        nullableDouble(rs, "weight_kg"),
        nullableDouble(rs, "sleep_hours"));
  }

  private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
    double value = rs.getDouble(column);
    return rs.wasNull() ? null : value;
  }
}
