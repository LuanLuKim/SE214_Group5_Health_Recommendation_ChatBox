package vn.edu.uit.se214.health.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.edu.uit.se214.health.integrations.PythonServiceClient;
import vn.edu.uit.se214.health.safety.api.SafetyEvaluator;

/** Database-backed beans exist only with DATA_MODE=postgres. */
@Configuration
@Profile("postgres")
class SafetyPersistenceConfig {
  @Bean
  RuleRepository ruleRepository(JdbcClient jdbc, ObjectMapper json) {
    return new RuleRepository(jdbc, json);
  }

  @Bean
  RuleDraftService ruleDraftService(RuleRepository repository) {
    return new RuleDraftService(repository);
  }

  @Bean
  @Primary
  SafetyEvaluator ruleBasedSafetyEvaluator(
      RuleRepository repository, @Qualifier("aiService") PythonServiceClient ai, ObjectMapper json) {
    return new RuleBasedSafetyEvaluator(repository, ai, json);
  }
}
