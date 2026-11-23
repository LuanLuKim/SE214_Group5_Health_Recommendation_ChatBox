package vn.edu.uit.se214.health.knowledge.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The loader is a one-off command, not part of normal start-up: it exists only with DATA_MODE=postgres
 * and KNOWLEDGE_INGEST_ENABLED=true, runs after the Flyway migrations, and logs counts only.
 */
@Configuration
@Profile("postgres")
@ConditionalOnProperty(name = "app.knowledge.ingest.enabled", havingValue = "true")
class KnowledgeIngestConfig {
  private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestConfig.class);

  @Bean
  KnowledgeIngest knowledgeIngest(
      JdbcClient jdbc, ObjectMapper json, PlatformTransactionManager transactions, IngestProperties properties) {
    return new KnowledgeIngest(new IngestSource(json, properties.translationModel()),
        new KnowledgeIngestRepository(jdbc), new TransactionTemplate(transactions));
  }

  @Bean
  ApplicationRunner knowledgeIngestRunner(KnowledgeIngest ingest, IngestProperties properties) {
    return args -> {
      List<Path> decisions = properties.reviewDecisions() == null ? List.of()
          : properties.reviewDecisions().stream().filter(v -> v != null && !v.isBlank()).map(Path::of).toList();
      KnowledgeIngest.Report report = ingest.run(
          path(properties.normalizedDir()), path(properties.translatedDir()), decisions, properties.reviewerId());
      KnowledgeIngest.describe(report).forEach(line -> log.info("Knowledge ingest: {}", line));
    };
  }

  private static Path path(String value) {
    return value == null || value.isBlank() ? null : Path.of(value);
  }
}
