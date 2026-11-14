package vn.edu.uit.se214.health.lifestyle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.edu.uit.se214.health.accounts.auth.AccessTokenService;
import vn.edu.uit.se214.health.support.TestDatabase;

/**
 * UC-13 over HTTP in DATA_MODE=postgres: the real Spring Security chain (HA_ACCESS cookie, CSRF)
 * in front of the seeded synthetic user and plan (database/seeds/001_synthetic.sql).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
class LifestyleApiTest {
  static final UUID SEEDED_USER = UUID.fromString("00000000-0000-4000-8000-000000000001");
  static final String SEEDED_ACTIVITY = "demo";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired AccessTokenService tokens;

  @Value("${app.lifestyle.zone}")
  String zone;

  String csrf;
  Cookie access;
  String today;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    try (Connection c = TestDatabase.server().getPostgresDatabase().getConnection();
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE lifestyle_api");
    }
    registry.add("spring.datasource.url", () -> TestDatabase.jdbcUrl("lifestyle_api"));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
    registry.add("app.data-mode", () -> "postgres");
    registry.add("app.seed-synthetic", () -> "true");
    registry.add("app.accounts.auth-token.secret", () -> "test-only-auth-token-secret-at-least-32-bytes");
  }

  @BeforeEach
  void signIn() throws Exception {
    jdbc.sql("DELETE FROM lifestyle.completions WHERE activity_id = ?").param(SEEDED_ACTIVITY).update();
    access = new Cookie("HA_ACCESS", tokens.issue(SEEDED_USER, Instant.now().plus(10, ChronoUnit.MINUTES)));
    csrf = cookie(mvc.perform(get("/api/v1/csrf")).andReturn(), "XSRF-TOKEN");
    today = LocalDate.now(ZoneId.of(zone)).toString();
  }

  MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder request) {
    return request.cookie(access, new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf);
  }

  String completion() {
    return "/api/v1/lifestyle/completions/" + SEEDED_ACTIVITY + "/" + today;
  }

  @Test
  void routesNeedAVerifiedSession() throws Exception {
    mvc.perform(get("/api/v1/lifestyle/plan"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("SESSION_INVALID"));
    mvc.perform(get("/api/v1/lifestyle/plan").cookie(new Cookie("HA_ACCESS", "forged")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void writesNeedTheCsrfToken() throws Exception {
    mvc.perform(put(completion()).cookie(access))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
    assertThat(stored()).isZero();
  }

  @Test
  void signedInUserMarksAndUnmarksTodaysActivity() throws Exception {
    mvc.perform(signed(get("/api/v1/lifestyle/plan")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.date").value(today))
        .andExpect(jsonPath("$.data.activities[0].id").value(SEEDED_ACTIVITY))
        .andExpect(jsonPath("$.data.activities[0].completed").value(false));

    for (int i = 0; i < 2; i++)
      mvc.perform(signed(put(completion())))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.completed").value(true))
          .andExpect(jsonPath("$.data.streak").value(1))
          .andExpect(jsonPath("$.data.progress.completedActivities").value(1));
    assertThat(stored()).isEqualTo(1);

    mvc.perform(signed(get("/api/v1/lifestyle/plan")))
        .andExpect(jsonPath("$.data.activities[0].completed").value(true))
        .andExpect(jsonPath("$.data.planStreak").value(1));

    mvc.perform(signed(delete(completion())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.completed").value(false));
    assertThat(stored()).isZero();
  }

  @Test
  void errorsUseTheSharedEnvelope() throws Exception {
    mvc.perform(signed(put("/api/v1/lifestyle/completions/unknown/" + today)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_IN_PLAN"))
        .andExpect(jsonPath("$.error.requestId").exists());
    mvc.perform(signed(put("/api/v1/lifestyle/completions/" + SEEDED_ACTIVITY + "/not-a-date")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_COMPLETION_DATE"));

    Cookie noPlan =
        new Cookie(
            "HA_ACCESS",
            tokens.issue(
                UUID.fromString("00000000-0000-4000-8000-000000000002"), Instant.now().plus(10, ChronoUnit.MINUTES)));
    mvc.perform(get("/api/v1/lifestyle/plan").cookie(noPlan))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("LIFESTYLE_PLAN_NOT_FOUND"));
  }

  int stored() {
    return jdbc.sql("SELECT count(*) FROM lifestyle.completions WHERE activity_id = ?")
        .param(SEEDED_ACTIVITY)
        .query(Integer.class)
        .single();
  }

  static String cookie(MvcResult result, String name) {
    return result.getResponse().getHeaders("Set-Cookie").stream()
        .filter(value -> value.startsWith(name + "="))
        .map(value -> value.substring(name.length() + 1, value.indexOf(';')))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing " + name + " cookie"));
  }
}
