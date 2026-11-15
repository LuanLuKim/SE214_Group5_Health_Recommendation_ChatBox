package vn.edu.uit.se214.health.lifestyle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.edu.uit.se214.health.accounts.auth.AccessTokenService;
import vn.edu.uit.se214.health.support.TestDatabase;

/**
 * UC-12 over HTTP in DATA_MODE=postgres with synthetic seeding: the seeded user, the [TEST]
 * templates of database/seeds/003_lifestyle_templates.sql and the real security chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
class LifestylePlanApiTest {
  static final UUID SEEDED_USER = UUID.fromString("00000000-0000-4000-8000-000000000001");
  static final String SLEEP = "00000000-0000-4000-8000-000000000070";
  static final String MAINTAIN = "00000000-0000-4000-8000-000000000071";
  static final String DRAFT = "00000000-0000-4000-8000-000000000074";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired AccessTokenService tokens;

  String csrf;
  Cookie access;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    try (Connection c = TestDatabase.server().getPostgresDatabase().getConnection();
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE lifestyle_plan_api");
    }
    registry.add("spring.datasource.url", () -> TestDatabase.jdbcUrl("lifestyle_plan_api"));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
    registry.add("app.data-mode", () -> "postgres");
    registry.add("app.seed-synthetic", () -> "true");
    registry.add("app.accounts.auth-token.secret", () -> "test-only-auth-token-secret-at-least-32-bytes");
  }

  @BeforeEach
  void signIn() throws Exception {
    access = new Cookie("HA_ACCESS", tokens.issue(SEEDED_USER, Instant.now().plus(10, ChronoUnit.MINUTES)));
    csrf = LifestyleApiTest.cookie(mvc.perform(get("/api/v1/csrf")).andReturn(), "XSRF-TOKEN");
  }

  MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder request) {
    return request.cookie(access, new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf);
  }

  MockHttpServletRequestBuilder choose(String templateId) {
    return signed(put("/api/v1/lifestyle/plan"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"templateId\":\"" + templateId + "\"}");
  }

  @Test
  void routesNeedAVerifiedSessionAndWritesNeedCsrf() throws Exception {
    mvc.perform(get("/api/v1/lifestyle/templates"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("SESSION_INVALID"));
    mvc.perform(
            put("/api/v1/lifestyle/plan")
                .cookie(access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"templateId\":\"" + SLEEP + "\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
  }

  @Test
  void listsApprovedTemplatesOfTheGoalOnly() throws Exception {
    mvc.perform(signed(get("/api/v1/lifestyle/templates").param("goal", "SLEEP")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.goal").value("SLEEP"))
        .andExpect(jsonPath("$.data.profileMissing").value(false))
        .andExpect(jsonPath("$.data.templates[*].id").value(hasItem(SLEEP)))
        .andExpect(jsonPath("$.data.templates[*].id").value(not(hasItem(DRAFT))))
        .andExpect(jsonPath("$.data.templates[*].goal").value(everyItem(is("SLEEP"))));
    mvc.perform(signed(get("/api/v1/lifestyle/templates").param("goal", "swim")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_GOAL"));
  }

  @Test
  void previewThenApplyAndSwitchThePlan() throws Exception {
    mvc.perform(signed(get("/api/v1/lifestyle/templates/" + SLEEP)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.activities[0].id").value("sleep-time"))
        .andExpect(jsonPath("$.data.advice").isNotEmpty());
    mvc.perform(signed(get("/api/v1/lifestyle/templates/" + DRAFT)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("LIFESTYLE_TEMPLATE_NOT_FOUND"));

    mvc.perform(choose(SLEEP))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.templateId").value(SLEEP))
        .andExpect(jsonPath("$.data.changed").value(true));
    mvc.perform(choose(SLEEP)).andExpect(jsonPath("$.data.changed").value(false));
    mvc.perform(choose(MAINTAIN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.changed").value(true))
        .andExpect(jsonPath("$.data.previousPlanId").isNotEmpty());

    mvc.perform(signed(get("/api/v1/lifestyle/plan")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.templateId").value(MAINTAIN))
        .andExpect(jsonPath("$.data.activities[0].id").value("walk"));
    assertThat(
            jdbc.sql("SELECT count(*) FROM lifestyle.plans WHERE user_id = ? AND ended_on IS NULL")
                .param(SEEDED_USER)
                .query(Integer.class)
                .single())
        .isEqualTo(1);

    mvc.perform(choose(DRAFT))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("LIFESTYLE_TEMPLATE_NOT_FOUND"));
    mvc.perform(signed(put("/api/v1/lifestyle/plan")).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_TEMPLATE_ID"));
  }
}
