package lifestyle;

import system.HealthAssistantApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import accounts.auth.AccessTokenService;
import support.TestDatabase;

/**
 * UC-12 over HTTP in DATA_MODE=postgres with synthetic seeding: the seeded user, the [TEST]
 * templates of database/seeds/003_lifestyle_templates.sql and the real security chain.
 */
@SpringBootTest(classes = HealthAssistantApplication.class)
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
  @Autowired ObjectMapper json;
  @Autowired DataSource dataSource;
  @Autowired LifestyleProperties lifestyle;

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
    registry.add("app.lifestyle.history-limit", () -> "2");
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
  @Transactional
  void consentIsCheckedAgainAtConfirmation() throws Exception {
    mvc.perform(choose(SLEEP)).andExpect(status().isOk());
    mvc.perform(signed(get("/api/v1/lifestyle/templates/" + MAINTAIN))).andExpect(status().isOk());
    assertThat(jdbc.sql("UPDATE accounts.consents SET health_data_processing_accepted = false WHERE user_id = ?")
        .param(SEEDED_USER).update()).isPositive();
    mvc.perform(choose(MAINTAIN)).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("HEALTH_CONSENT_REQUIRED"));
    assertThat(jdbc.sql("SELECT template_id FROM lifestyle.plans WHERE user_id = ? AND ended_on IS NULL")
        .param(SEEDED_USER).query(UUID.class).single()).isEqualTo(UUID.fromString(SLEEP));
  }

  @Test
  @Transactional
  void aTemplateRetiredAfterPreviewCannotBeApplied() throws Exception {
    mvc.perform(choose(SLEEP)).andExpect(status().isOk());
    mvc.perform(signed(get("/api/v1/lifestyle/templates/" + MAINTAIN))).andExpect(status().isOk());
    assertThat(jdbc.sql("UPDATE lifestyle.templates SET review_status = 'RETIRED' WHERE id = ?")
        .param(UUID.fromString(MAINTAIN)).update()).isEqualTo(1);
    mvc.perform(choose(MAINTAIN)).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("LIFESTYLE_TEMPLATE_NOT_FOUND"));
    mvc.perform(signed(get("/api/v1/lifestyle/plan")))
        .andExpect(jsonPath("$.data.templateId").value(SLEEP));
  }

  @Test
  @Transactional
  void afterASwitchAnOldActivityCannotBeMarked() throws Exception {
    mvc.perform(choose(SLEEP)).andExpect(status().isOk());
    String current = mvc.perform(signed(get("/api/v1/lifestyle/plan"))).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String date = json.readTree(current).at("/data/date").asText();
    assertThat(date).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    String done = "/api/v1/lifestyle/completions/sleep-time/" + date;
    mvc.perform(signed(put(done))).andExpect(status().isOk());
    mvc.perform(choose(MAINTAIN)).andExpect(status().isOk());
    mvc.perform(signed(put(done))).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_IN_PLAN"));
    assertThat(jdbc.sql("""
        SELECT count(*) FROM lifestyle.completions c
        JOIN lifestyle.plans p ON p.id = c.plan_id
        WHERE p.user_id = ? AND p.template_id = ? AND p.ended_on IS NOT NULL
          AND c.activity_id = 'sleep-time' AND c.completed_on = ?::date
        """).params(SEEDED_USER, UUID.fromString(SLEEP), date).query(Integer.class).single())
        .isEqualTo(1);
  }

  @Test
  @Transactional
  void anAllergyAddedAfterPreviewBlocksConfirmation() throws Exception {
    String gain = "00000000-0000-4000-8000-000000000072";
    mvc.perform(choose(SLEEP)).andExpect(status().isOk());
    mvc.perform(signed(get("/api/v1/lifestyle/templates/" + gain))).andExpect(status().isOk());
    assertThat(jdbc.sql("""
        UPDATE health.profiles SET food_allergies = ARRAY['đậu phộng'] WHERE user_id = ?
        """).param(SEEDED_USER).update()).isEqualTo(1);

    mvc.perform(choose(gain)).andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("TEMPLATE_HAS_ALLERGEN"));
    assertThat(jdbc.sql("""
        SELECT template_id FROM lifestyle.plans WHERE user_id = ? AND ended_on IS NULL
        """).param(SEEDED_USER).query(UUID.class).single()).isEqualTo(UUID.fromString(SLEEP));
    assertThat(jdbc.sql("SELECT count(*) FROM lifestyle.plans WHERE user_id = ? AND template_id = ?::uuid")
        .params(SEEDED_USER, gain).query(Integer.class).single()).isZero();
  }

  @Test
  @Transactional
  void completionsDoNotCarryOverToAPlanWithTheSameActivityId() throws Exception {
    String activities = """
        [{"id":"sleep-time","title":"[TEST] Same activity id in a different plan"}]
        """;
    assertThat(jdbc.sql("UPDATE lifestyle.templates SET activities = ?::jsonb WHERE id = ?::uuid")
        .params(activities, MAINTAIN).update()).isEqualTo(1);
    mvc.perform(choose(SLEEP)).andExpect(status().isOk());
    var current = mvc.perform(signed(get("/api/v1/lifestyle/plan"))).andExpect(status().isOk()).andReturn();
    String date = json.readTree(current.getResponse().getContentAsString()).at("/data/date").asText();
    String done = "/api/v1/lifestyle/completions/sleep-time/" + date;
    mvc.perform(signed(put(done))).andExpect(status().isOk());

    mvc.perform(choose(MAINTAIN)).andExpect(status().isOk());
    mvc.perform(signed(get("/api/v1/lifestyle/plan"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.activities[0].id").value("sleep-time"))
        .andExpect(jsonPath("$.data.activities[0].completed").value(false));
    mvc.perform(signed(put(done))).andExpect(status().isOk());
    assertThat(jdbc.sql("""
        SELECT p.template_id FROM lifestyle.completions c
        JOIN lifestyle.plans p ON p.id = c.plan_id
        WHERE p.user_id = ? AND c.activity_id = 'sleep-time' AND c.completed_on = ?::date
        """).params(SEEDED_USER, date).query(UUID.class).list())
        .containsExactlyInAnyOrder(UUID.fromString(SLEEP), UUID.fromString(MAINTAIN));
  }

  @Test
  @Transactional
  void removingARequiredProfileAfterPreviewBlocksConfirmation() throws Exception {
    String requiresProfile = "00000000-0000-4000-8000-000000000073";
    mvc.perform(choose(SLEEP)).andExpect(status().isOk());
    mvc.perform(signed(get("/api/v1/lifestyle/templates/" + requiresProfile))).andExpect(status().isOk());
    assertThat(jdbc.sql("DELETE FROM health.profiles WHERE user_id = ?")
        .param(SEEDED_USER).update()).isEqualTo(1);

    mvc.perform(choose(requiresProfile)).andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("HEALTH_PROFILE_REQUIRED"));
    assertThat(jdbc.sql("SELECT template_id FROM lifestyle.plans WHERE user_id = ? AND ended_on IS NULL")
        .param(SEEDED_USER).query(UUID.class).single()).isEqualTo(UUID.fromString(SLEEP));
    assertThat(jdbc.sql("SELECT count(*) FROM lifestyle.plans WHERE user_id = ? AND template_id = ?::uuid")
        .params(SEEDED_USER, requiresProfile).query(Integer.class).single()).isZero();
  }

  @Test
  @Transactional
  void confirmingTheActivePlanAgainKeepsItAndItsProgress() throws Exception {
    var applied = mvc.perform(choose(SLEEP)).andExpect(status().isOk()).andReturn();
    String planId = json.readTree(applied.getResponse().getContentAsString()).at("/data/planId").asText();
    var plan = mvc.perform(signed(get("/api/v1/lifestyle/plan"))).andExpect(status().isOk()).andReturn();
    String date = json.readTree(plan.getResponse().getContentAsString()).at("/data/date").asText();
    mvc.perform(signed(put("/api/v1/lifestyle/completions/sleep-time/" + date))).andExpect(status().isOk());
    int before = jdbc.sql("SELECT count(*) FROM lifestyle.plans WHERE user_id = ?")
        .param(SEEDED_USER).query(Integer.class).single();

    mvc.perform(choose(SLEEP)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.changed").value(false))
        .andExpect(jsonPath("$.data.planId").value(planId))
        .andExpect(jsonPath("$.data.previousPlanId").doesNotExist());
    mvc.perform(signed(get("/api/v1/lifestyle/plan"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.activities[0].completed").value(true));
    assertThat(jdbc.sql("SELECT count(*) FROM lifestyle.plans WHERE user_id = ?")
        .param(SEEDED_USER).query(Integer.class).single()).isEqualTo(before);
    assertThat(jdbc.sql("""
        SELECT count(*) FROM lifestyle.completions WHERE plan_id = ?::uuid AND activity_id = 'sleep-time'
        """).param(planId).query(Integer.class).single()).isEqualTo(1);
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

  @Test
  void twoConfirmationsAtOnceLeaveOneActivePlan() throws Exception {
    UUID user = UUID.randomUUID();
    jdbc.sql("INSERT INTO accounts.users (id, email, display_name, is_synthetic) VALUES (?, ?, ?, true)")
        .params(user, user + "@example.invalid", "[TEST] UC12 concurrent confirmation").update();
    jdbc.sql("INSERT INTO accounts.user_roles (user_id, role) VALUES (?, 'USER')").param(user).update();
    try {
      jdbc.sql("""
          INSERT INTO accounts.consents (user_id, terms_version, adult_confirmed, accepted_at,
            health_data_processing_accepted, non_diagnostic_acknowledged)
          VALUES (?, 'synthetic-v2', true, now(), true, true)
          """).param(user).update();
      access = new Cookie("HA_ACCESS", tokens.issue(user, Instant.now().plus(10, ChronoUnit.MINUTES)));
      var initial = mvc.perform(choose(SLEEP)).andExpect(status().isOk()).andReturn();
      UUID initialId = UUID.fromString(json.readTree(initial.getResponse().getContentAsString())
          .at("/data/planId").asText());
      mvc.perform(signed(get("/api/v1/lifestyle/templates/" + MAINTAIN))).andExpect(status().isOk());

      List<MvcResult> confirmations;
      try (var requests = Executors.newFixedThreadPool(2)) {
        try (Connection lock = dataSource.getConnection();
            var statement = lock.prepareStatement("SELECT id FROM lifestyle.plans WHERE id = ? FOR UPDATE")) {
          lock.setAutoCommit(false);
          statement.setObject(1, initialId);
          try (var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
          }
          var first = requests.submit(() -> mvc.perform(choose(MAINTAIN)).andReturn());
          var second = requests.submit(() -> mvc.perform(choose(MAINTAIN)).andReturn());
          try {
            awaitBothRequestsWaitingForPlanLock();
          } finally {
            lock.rollback();
          }
          confirmations = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
      }
      var ordered = confirmations.stream()
          .sorted(Comparator.comparingInt(result -> result.getResponse().getStatus())).toList();
      status().isOk().match(ordered.get(0));
      jsonPath("$.data.changed").value(true).match(ordered.get(0));
      jsonPath("$.data.templateId").value(MAINTAIN).match(ordered.get(0));
      jsonPath("$.data.previousPlanId").value(initialId.toString()).match(ordered.get(0));
      status().isConflict().match(ordered.get(1));
      jsonPath("$.error.code").value("LIFESTYLE_PLAN_CONFLICT").match(ordered.get(1));

      UUID activeId = jdbc.sql("""
          SELECT id FROM lifestyle.plans WHERE user_id = ? AND ended_on IS NULL AND template_id = ?::uuid
          """).params(user, MAINTAIN).query(UUID.class).single();
      jsonPath("$.data.planId").value(activeId.toString()).match(ordered.get(0));
      assertThat(jdbc.sql("""
          SELECT id FROM lifestyle.plans WHERE user_id = ? AND ended_on IS NOT NULL
          """).param(user).query(UUID.class).list()).containsExactly(initialId);
      assertThat(jdbc.sql("SELECT count(*) FROM lifestyle.plans WHERE user_id = ?")
          .param(user).query(Integer.class).single()).isEqualTo(2);
    } finally {
      jdbc.sql("DELETE FROM lifestyle.plans WHERE user_id = ?").param(user).update();
      jdbc.sql("DELETE FROM accounts.users WHERE id = ?").param(user).update();
    }
  }

  // Test round 2 (#166): history of ended plans (#159) and progress after choosing a template again.

  String planId(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString()).at("/data/planId").asText();
  }

  @Test
  @Transactional
  void historyShowsTheUsersEndedPlansUpToTheLimitAndNeedsConsent() throws Exception {
    int limit = lifestyle.historyLimit();
    String[] switches = {MAINTAIN, SLEEP, MAINTAIN, SLEEP, MAINTAIN};
    assertThat(switches.length - 1).as("more ended plans than the limit").isGreaterThan(limit);
    for (String template : switches) mvc.perform(choose(template)).andExpect(status().isOk());

    mvc.perform(signed(get("/api/v1/lifestyle/plans/history")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.plans.length()").value(limit))
        .andExpect(jsonPath("$.data.plans[*].endedOn", everyItem(notNullValue())));
    assertThat(jdbc.sql("SELECT count(*) FROM lifestyle.plans WHERE user_id = ? AND ended_on IS NULL")
            .param(SEEDED_USER)
            .query(Integer.class)
            .single())
        .as("the active plan is never part of the history")
        .isEqualTo(1);

    jdbc.sql("UPDATE accounts.consents SET health_data_processing_accepted = false WHERE user_id = ?")
        .param(SEEDED_USER)
        .update();
    mvc.perform(signed(get("/api/v1/lifestyle/plans/history")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("HEALTH_CONSENT_REQUIRED"));
  }

  @Test
  @Transactional
  void choosingATemplateAgainStartsANewPlanWithoutTheOldProgress() throws Exception {
    mvc.perform(choose(MAINTAIN)).andExpect(status().isOk());
    String first = planId(mvc.perform(choose(SLEEP)).andExpect(status().isOk()).andReturn());
    String today = json.readTree(mvc.perform(signed(get("/api/v1/lifestyle/plan"))).andReturn().getResponse()
            .getContentAsString())
        .at("/data/date")
        .asText();
    for (String activity : new String[] {"sleep-time", "no-screen"})
      mvc.perform(signed(put("/api/v1/lifestyle/completions/" + activity + "/" + today))).andExpect(status().isOk());
    mvc.perform(signed(get("/api/v1/lifestyle/plan"))).andExpect(jsonPath("$.data.planStreak").value(1));

    mvc.perform(choose(MAINTAIN)).andExpect(status().isOk());
    String again = planId(mvc.perform(choose(SLEEP)).andExpect(status().isOk()).andReturn());
    assertThat(again).isNotEqualTo(first);
    mvc.perform(signed(get("/api/v1/lifestyle/plan")))
        .andExpect(jsonPath("$.data.planId").value(again))
        .andExpect(jsonPath("$.data.planStreak").value(0))
        .andExpect(jsonPath("$.data.activities[*].completed", everyItem(is(false))));
    // The earlier plan keeps its own completions (shown as figures by GET /plans/history).
    assertThat(jdbc.sql("SELECT count(*) FROM lifestyle.completions WHERE plan_id = ?::uuid")
            .param(first)
            .query(Integer.class)
            .single())
        .isEqualTo(2);
    assertThat(jdbc.sql("SELECT count(*) FROM lifestyle.completions WHERE plan_id = ?::uuid")
            .param(again)
            .query(Integer.class)
            .single())
        .isZero();
  }

  private void awaitBothRequestsWaitingForPlanLock() throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    int waiting;
    do {
      waiting = jdbc.sql("""
          SELECT count(*) FROM pg_stat_activity
          WHERE datname = current_database() AND wait_event_type = 'Lock'
            AND query LIKE 'SELECT id, template_id FROM lifestyle.plans%'
            AND query LIKE '%FOR UPDATE%'
          """).query(Integer.class).single();
      if (waiting == 2) return;
      Thread.sleep(20);
    } while (System.nanoTime() < deadline);
    assertThat(waiting).as("confirmation SQL statements blocked on the original plan").isEqualTo(2);
  }
}
