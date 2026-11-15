package vn.edu.uit.se214.health.lifestyle;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.uit.se214.health.accounts.api.ConsentQuery;
import vn.edu.uit.se214.health.health.api.HealthProfileQuery;
import vn.edu.uit.se214.health.shared.AppProperties;

/** Database-backed beans exist only with DATA_MODE=postgres; in mock mode the routes answer 503. */
@Configuration
@Profile("postgres")
class LifestylePersistenceConfig {
  @Bean
  LifestyleRepository lifestyleRepository(JdbcClient jdbc, PlatformTransactionManager transactions) {
    return new LifestyleRepository(jdbc, new TransactionTemplate(transactions));
  }

  @Bean
  LifestyleService lifestyleService(
      LifestyleRepository repository, ObjectMapper json, LifestyleProperties properties) {
    return new LifestyleService(repository, json, clock(properties));
  }

  @Bean
  LifestylePlanService lifestylePlanService(
      LifestyleRepository repository,
      ObjectMapper json,
      LifestyleProperties properties,
      HealthProfileQuery profiles,
      ConsentQuery consents,
      AppProperties app) {
    return new LifestylePlanService(
        repository, json, clock(properties), profiles, consents, app.seedSynthetic());
  }

  /** Not a bean, so no other module receives this zone by accident. An unknown zone id fails at startup. */
  private static Clock clock(LifestyleProperties properties) {
    return Clock.system(ZoneId.of(properties.zone()));
  }
}
