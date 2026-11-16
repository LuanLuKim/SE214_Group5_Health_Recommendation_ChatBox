package vn.edu.uit.se214.health.accounts.persistence;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.edu.uit.se214.health.accounts.api.ConsentQuery;

/**
 * Read-only implementation of {@link ConsentQuery} over accounts.consents: the most recent consent
 * decides. Added for UC-12 (M6); M5 owns it.
 */
@Repository
@Profile("postgres")
public class JdbcConsentQuery implements ConsentQuery {
  private final JdbcClient jdbc;

  public JdbcConsentQuery(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean healthDataProcessingAccepted(UUID userId) {
    return jdbc.sql(
            """
            SELECT health_data_processing_accepted FROM accounts.consents
            WHERE user_id = ?
            ORDER BY accepted_at DESC
            LIMIT 1
            """)
        .param(userId)
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }
}
