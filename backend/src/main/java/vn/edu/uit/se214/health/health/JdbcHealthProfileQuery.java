package vn.edu.uit.se214.health.health;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.edu.uit.se214.health.health.api.HealthProfileQuery;

/**
 * Read-only implementation of {@link HealthProfileQuery} over health.profiles. Added for UC-12
 * (M6) before UC-04; M3 owns it and may move it behind its own profile service.
 */
@Repository
@Profile("postgres")
public class JdbcHealthProfileQuery implements HealthProfileQuery {
  private final JdbcClient jdbc;

  public JdbcHealthProfileQuery(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Set<String>> foodAllergies(UUID userId) {
    return jdbc.sql("SELECT food_allergies FROM health.profiles WHERE user_id = ?")
        .param(userId)
        .query((rs, row) -> normalised(rs.getArray("food_allergies")))
        .optional();
  }

  private static Set<String> normalised(Array array) throws SQLException {
    if (array == null) return Set.of();
    return Arrays.stream((String[]) array.getArray())
        .map(HealthProfileQuery::normaliseAllergen)
        .filter(value -> !value.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }
}
