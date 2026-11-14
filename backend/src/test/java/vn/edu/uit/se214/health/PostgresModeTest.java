package vn.edu.uit.se214.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.uit.se214.health.support.TestDatabase;

/**
 * DATA_MODE=postgres end to end: Flyway runs database/migrations on startup, the synthetic seed
 * runs, and public search only returns APPROVED, verified content.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
class PostgresModeTest {
  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    try (Connection c = TestDatabase.server().getPostgresDatabase().getConnection();
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE postgres_mode");
    }
    registry.add("spring.datasource.url", () -> TestDatabase.jdbcUrl("postgres_mode"));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
    registry.add("app.data-mode", () -> "postgres");
    registry.add("app.seed-synthetic", () -> "true");
    registry.add("app.accounts.auth-token.secret", () -> "test-only-auth-token-secret-at-least-32-bytes");
  }

  @Test
  void searchReadsSeededDatabaseAndHidesDrafts() throws Exception {
    mvc.perform(get("/api/v1/knowledge/articles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.meta.status").value("DATABASE_RESULTS"))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].id").value("00000000-0000-4000-8000-000000000010"))
        .andExpect(jsonPath("$.data[0].source.verifiedAt").value("2026-10-29T00:00:00.000Z"));
    mvc.perform(get("/api/v1/knowledge/articles").param("q", "Bản nháp"))
        .andExpect(jsonPath("$.data.length()").value(0));
    mvc.perform(get("/api/v1/knowledge/facilities").param("q", "giả lập"))
        .andExpect(jsonPath("$.data[0].specialties[0]").value("Chuyên khoa thử nghiệm"));
    // LIKE wildcards in user input are matched literally.
    mvc.perform(get("/api/v1/knowledge/facilities").param("q", "%"))
        .andExpect(jsonPath("$.data.length()").value(0));
    // PostgreSQL rejects NUL in text; the query must be refused before it reaches the database.
    mvc.perform(get("/api/v1/knowledge/articles").param("q", "a\u0000b"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_QUERY"));
  }
}
