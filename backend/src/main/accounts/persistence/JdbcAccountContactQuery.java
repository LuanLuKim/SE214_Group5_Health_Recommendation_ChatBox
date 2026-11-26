package accounts.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import accounts.api.AccountContactQuery;

/**
 * Read-only implementation of {@link AccountContactQuery} over accounts.users. Added for UC-14
 * (M6); M5 owns it.
 */
@Repository
@Profile("postgres")
public class JdbcAccountContactQuery implements AccountContactQuery {
  private final JdbcClient jdbc;

  public JdbcAccountContactQuery(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<String> email(UUID userId) {
    return jdbc.sql("SELECT email FROM accounts.users WHERE id = ? AND status = 'ACTIVE'")
        .param(userId)
        .query(String.class)
        .optional();
  }
}
