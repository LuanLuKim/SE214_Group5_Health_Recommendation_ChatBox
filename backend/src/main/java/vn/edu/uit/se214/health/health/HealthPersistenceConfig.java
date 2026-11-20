package vn.edu.uit.se214.health.health;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.uit.se214.health.accounts.api.ConsentQuery;

/** Database-backed beans exist only with DATA_MODE=postgres; in mock mode the routes answer 503. */
@Configuration
@Profile("postgres")
class HealthPersistenceConfig {
  /** "Today" for the recorded weight is the calendar day in Vietnam. */
  private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  @Bean
  HealthProfileRepository healthProfileRepository(JdbcClient jdbc, PlatformTransactionManager transactions) {
    return new HealthProfileRepository(jdbc, new TransactionTemplate(transactions));
  }

  @Bean
  HealthProfileService healthProfileService(HealthProfileRepository repository, ConsentQuery consents) {
    return new HealthProfileService(repository, consents, Clock.system(ZONE));
  }
}
