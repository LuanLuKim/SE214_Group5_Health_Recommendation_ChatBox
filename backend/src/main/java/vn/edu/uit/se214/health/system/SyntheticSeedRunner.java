package vn.edu.uit.se214.health.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import vn.edu.uit.se214.health.shared.AppProperties;

/**
 * Development only: after Flyway migrations, runs database/seeds/*.sql (idempotent synthetic
 * records with fixed IDs). Enable with DATA_MODE=postgres and SEED_SYNTHETIC=true.
 */
@Component
@Profile("postgres")
@ConditionalOnProperty(name = "app.seed-synthetic", havingValue = "true")
public class SyntheticSeedRunner implements ApplicationRunner {
  private final DataSource dataSource;
  private final Path seeds;

  public SyntheticSeedRunner(DataSource dataSource, AppProperties properties) {
    this.dataSource = dataSource;
    this.seeds = Path.of(properties.databaseDir(), "seeds");
  }

  @Override
  public void run(ApplicationArguments args) throws IOException {
    if ("production".equalsIgnoreCase(System.getenv("APP_ENV")))
      throw new IllegalStateException("Synthetic seeding is disabled in production.");
    var populator = new ResourceDatabasePopulator();
    populator.setSqlScriptEncoding("UTF-8");
    for (Path file : sqlFiles(seeds)) populator.addScript(new FileSystemResource(file));
    populator.execute(dataSource);
  }

  public static List<Path> sqlFiles(Path directory) throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      return files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
    }
  }
}
