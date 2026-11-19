package vn.edu.uit.se214.health.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * One embedded PostgreSQL for the whole test run (no Docker). Each call to {@link #fresh}
 * creates an empty database and applies database/migrations with Flyway.
 */
public final class TestDatabase {
  public static final Path DATABASE_DIR = Path.of("..", "database");
  private static final AtomicInteger COUNTER = new AtomicInteger();
  private static EmbeddedPostgres postgres;

  private TestDatabase() {}

  public static synchronized EmbeddedPostgres server() {
    if (postgres == null) {
      try {
        postgres = EmbeddedPostgres.builder().start();
      } catch (IOException e) {
        throw new IllegalStateException("Cannot start embedded PostgreSQL", e);
      }
      Runtime.getRuntime().addShutdownHook(new Thread(TestDatabase::close));
    }
    return postgres;
  }

  /** A new empty database with every migration applied. */
  public static DataSource fresh() {
    String name = "t" + COUNTER.incrementAndGet();
    try (Connection c = server().getPostgresDatabase().getConnection();
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE " + name);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    DataSource dataSource = server().getDatabase("postgres", name);
    migrate(dataSource);
    return dataSource;
  }

  public static void migrate(DataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("filesystem:" + DATABASE_DIR.resolve("migrations"))
        .load()
        .migrate();
  }

  public static String jdbcUrl(String database) {
    return server().getJdbcUrl("postgres", database);
  }

  private static void close() {
    try {
      if (postgres != null) postgres.close();
    } catch (IOException ignored) {
      // Test JVM is exiting.
    }
  }
}
