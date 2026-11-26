package health;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** SQL for health.profiles and the weight column of health.daily_metrics. Values are always bound. */
public class HealthProfileRepository {
  record ProfileRow(Double heightCm, Integer age, String sex, List<String> foodAllergies) {}

  record WeightRow(LocalDate recordedOn, Double weightKg) {}

  private final JdbcClient jdbc;
  private final TransactionOperations transactions;

  /** {@code transactions} makes the profile and today's weight one write: both stored or neither. */
  public HealthProfileRepository(JdbcClient jdbc, TransactionOperations transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  Optional<ProfileRow> findProfile(UUID userId) {
    return jdbc.sql("SELECT height_cm, age, sex, food_allergies FROM health.profiles WHERE user_id = ?")
        .param(userId)
        .query(
            (rs, row) ->
                new ProfileRow(
                    nullableDouble(rs, "height_cm"),
                    nullableInt(rs, "age"),
                    rs.getString("sex"),
                    allergies(rs.getArray("food_allergies"))))
        .optional();
  }

  /** The most recent recorded weight, whichever day it was recorded. */
  Optional<WeightRow> latestWeight(UUID userId) {
    return jdbc.sql(
            """
            SELECT recorded_on, weight_kg FROM health.daily_metrics
            WHERE user_id = ? AND weight_kg IS NOT NULL
            ORDER BY recorded_on DESC LIMIT 1
            """)
        .param(userId)
        .query((rs, row) -> new WeightRow(rs.getObject("recorded_on", LocalDate.class), nullableDouble(rs, "weight_kg")))
        .optional();
  }

  /**
   * Upserts the profile (one row per user) and today's weight. Sleep hours already recorded for
   * today are left alone. Repeating the same call changes nothing (NFR-04).
   */
  void save(
      UUID userId,
      double heightCm,
      int age,
      String sex,
      List<String> foodAllergies,
      double weightKg,
      LocalDate today) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.sql(
                  """
                  INSERT INTO health.profiles (user_id, height_cm, age, sex, food_allergies)
                  VALUES (?, ?, ?, ?, ?::text[])
                  ON CONFLICT (user_id) DO UPDATE SET
                    height_cm = EXCLUDED.height_cm, age = EXCLUDED.age,
                    sex = EXCLUDED.sex, food_allergies = EXCLUDED.food_allergies
                  """)
              .params(userId, heightCm, age, sex, arrayLiteral(foodAllergies))
              .update();
          jdbc.sql(
                  """
                  INSERT INTO health.daily_metrics (user_id, recorded_on, weight_kg)
                  VALUES (?, ?, ?)
                  ON CONFLICT (user_id, recorded_on) DO UPDATE SET weight_kg = EXCLUDED.weight_kg
                  """)
              .params(userId, today, weightKg)
              .update();
        });
  }

  /** Removes the profile row only; daily metrics belong to UC-05. */
  int deleteProfile(UUID userId) {
    return jdbc.sql("DELETE FROM health.profiles WHERE user_id = ?").param(userId).update();
  }

  private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
    double value = rs.getDouble(column);
    return rs.wasNull() ? null : value;
  }

  private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  private static List<String> allergies(Array array) throws SQLException {
    if (array == null) return List.of();
    return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
  }

  /** PostgreSQL array literal with every element quoted and escaped. */
  private static String arrayLiteral(List<String> values) {
    return "{"
        + String.join(
            ",",
            values.stream()
                .map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .toList())
        + "}";
  }
}
