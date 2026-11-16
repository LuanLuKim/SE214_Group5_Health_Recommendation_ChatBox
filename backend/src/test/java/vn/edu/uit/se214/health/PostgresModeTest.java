package vn.edu.uit.se214.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.edu.uit.se214.health.accounts.auth.AccessTokenService;
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
  @Autowired JdbcTemplate jdbc;
  @Autowired AccessTokenService tokens;

  private Cookie adminAccess;
  private String csrf;

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
    registry.add("app.accounts.admin.bootstrap-enabled", () -> "true");
    registry.add("app.accounts.admin.email", () -> "admin@health.test");
    registry.add("app.accounts.admin.password", () -> "test-only-admin-password");
  }

  @org.junit.jupiter.api.BeforeEach
  void authenticateAsBootstrapAdmin() throws Exception {
    UUID adminId =
        jdbc.queryForObject(
            "SELECT id FROM accounts.users WHERE email = 'admin@health.test'", UUID.class);
    adminAccess =
        new Cookie(
            "HA_ACCESS",
            tokens.issue(adminId, Instant.now().plus(10, ChronoUnit.MINUTES)));
    MvcResult csrfResult = mvc.perform(get("/api/v1/csrf")).andReturn();
    csrf = csrfResult.getResponse().getCookie("XSRF-TOKEN").getValue();
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signed(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
    return request
        .cookie(adminAccess, new Cookie("XSRF-TOKEN", csrf))
        .header("X-XSRF-TOKEN", csrf);
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

  @Test
  void rbacAdminCanManageUsersRolesPermissionsAndAuditButRegularUsersCannot() throws Exception {
    Cookie userAccess =
        new Cookie(
            "HA_ACCESS",
            tokens.issue(
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                Instant.now().plus(10, ChronoUnit.MINUTES)));
    mvc.perform(get("/api/v1/accounts/roles").cookie(userAccess))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));

    mvc.perform(signed(get("/api/v1/accounts/users")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(4));

    mvc.perform(
            get("/api/v1/accounts/users")
                .cookie(
                    new Cookie(
                        "HA_ACCESS",
                        tokens.issue(
                            UUID.fromString("00000000-0000-4000-8000-000000000001"),
                            Instant.now().plus(10, ChronoUnit.MINUTES)))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));

    mvc.perform(
            signed(post("/api/v1/accounts/users"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"created-by-admin@example.test","displayName":"Tài khoản mới",
                     "password":"initial-password-123"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.email").value("created-by-admin@example.test"))
        .andExpect(jsonPath("$.data.roles[0]").value("USER"));

    mvc.perform(signed(get("/api/v1/accounts/roles")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(4))
        .andExpect(jsonPath("$.data[1].key").value("CONTENT_EXPERT"))
        .andExpect(jsonPath("$.data[1].permissions[0]").exists());

    mvc.perform(
            signed(post("/api/v1/accounts/permissions"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"key":"AUDIT_EXPORT","name":"Xuất nhật ký","description":"Xuất báo cáo audit"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.key").value("AUDIT_EXPORT"));

    mvc.perform(
            signed(post("/api/v1/accounts/roles"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"key":"AUDIT_VIEWER","name":"Người xem audit","description":"Vai trò kiểm thử",
                     "permissions":["AUDIT_EXPORT"]}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.permissions[0]").value("AUDIT_EXPORT"));

    mvc.perform(
            signed(put("/api/v1/accounts/roles/AUDIT_VIEWER/permissions"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"AUDIT_EXPORT\",\"RBAC_MANAGE\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.permissions.length()").value(2));

    mvc.perform(
            signed(put(
                    "/api/v1/accounts/users/00000000-0000-4000-8000-000000000001/roles"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"USER\",\"AUDIT_VIEWER\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.roles[0]").value("AUDIT_VIEWER"));

    mvc.perform(
            get("/api/v1/accounts/roles")
                .cookie(userAccess, new Cookie("XSRF-TOKEN", csrf)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(5));

    mvc.perform(
            signed(get("/api/v1/accounts/users"))
                .param("q", "user@example.invalid")
                .param("role", "AUDIT_VIEWER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].email").value("user@example.invalid"));

    mvc.perform(
            signed(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/api/v1/accounts/permissions/AUDIT_EXPORT")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("PERMISSION_IN_USE"));

    mvc.perform(
            signed(
                    patch(
                        "/api/v1/accounts/users/00000000-0000-4000-8000-000000000001/status"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"LOCKED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("LOCKED"));

    mvc.perform(
            signed(
                    patch(
                        "/api/v1/accounts/users/00000000-0000-4000-8000-000000000001/status"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ACTIVE"));

    mvc.perform(signed(get("/api/v1/accounts/audit-logs")).param("targetType", "USER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(5));
  }
}
