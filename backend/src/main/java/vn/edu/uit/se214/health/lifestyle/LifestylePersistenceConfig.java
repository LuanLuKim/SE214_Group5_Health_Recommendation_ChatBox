package vn.edu.uit.se214.health.lifestyle;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Database-backed beans exist only with DATA_MODE=postgres; in mock mode the routes answer 503. */
@Configuration
@Profile("postgres")
class LifestylePersistenceConfig {
  @Bean
  LifestyleRepository lifestyleRepository(JdbcClient jdbc) {
    return new LifestyleRepository(jdbc);
  }

  @Bean
  LifestyleService lifestyleService(
      LifestyleRepository repository, ObjectMapper json, LifestyleProperties properties) {
    // An unknown zone id fails at startup instead of silently using UTC.
    return new LifestyleService(repository, json, Clock.system(ZoneId.of(properties.zone())));
  }
}
