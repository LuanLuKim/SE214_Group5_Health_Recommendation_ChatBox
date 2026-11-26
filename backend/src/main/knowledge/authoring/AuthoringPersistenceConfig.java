package knowledge.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/** UC-15 needs the database: with DATA_MODE=mock these beans do not exist and the routes answer 503. */
@Configuration
@Profile("postgres")
class AuthoringPersistenceConfig {
  @Bean
  ArticleDrafts articleDrafts(JdbcClient jdbc, ObjectMapper json) {
    return new ArticleDrafts(new ArticleDraftRepository(jdbc), json);
  }

  @Bean
  TemplateDrafts templateDrafts(JdbcClient jdbc, ObjectMapper json) {
    return new TemplateDrafts(new TemplateDraftRepository(jdbc, json), json);
  }
}
