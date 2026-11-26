package lifestyle;

import system.HealthAssistantApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import accounts.api.ConsentQuery;
import accounts.auth.AccessTokenService;
import support.TestDatabase;

/**
 * UC-14 over HTTP in DATA_MODE=postgres with synthetic seeding. SMTP points at a host that is
 * never contacted: the worker is off here (ReminderDispatcherTest covers sending).
 */
@SpringBootTest(classes = HealthAssistantApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
class LifestyleReminderApiTest {
  static final UUID SEEDED_USER = UUID.fromString("00000000-0000-4000-8000-000000000001");
  static final UUID OTHER_USER = UUID.fromString("00000000-0000-4000-8000-000000000002");
  static final String SEEDED_REMINDER = "00000000-0000-4000-8000-000000000052";
  static final String REMINDERS = "/api/v1/lifestyle/reminders";
  static final String PLAN = "/api/v1/lifestyle/plan";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired AccessTokenService tokens;
  @Autowired ObjectMapper json;
  @Autowired ReminderRepository reminders;
  @Autowired ConsentQuery consents;
  @Autowired RetentionRepository retentionRepository;
  @Autowired RetentionProperties retentionProperties;

  String csrf;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    try (Connection c = TestDatabase.server().getPostgresDatabase().getConnection();
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE lifestyle_reminder_api");
    }
    registry.add("spring.datasource.url", () -> TestDatabase.jdbcUrl("lifestyle_reminder_api"));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
    registry.add("app.data-mode", () -> "postgres");
    registry.add("app.seed-synthetic", () -> "true");
    registry.add("app.accounts.auth-token.secret", () -> "test-only-auth-token-secret-at-least-32-bytes");
    registry.add("spring.mail.host", () -> "smtp.example.invalid");
    registry.add("app.lifestyle.reminders.from-address", () -> "reminders@example.invalid");
    registry.add("app.lifestyle.reminders.enabled", () -> "false");
    registry.add("app.lifestyle.reminders.max-per-user", () -> "20");
  }

  @BeforeEach
  void csrf() throws Exception {
    csrf = LifestyleApiTest.cookie(mvc.perform(get("/api/v1/csrf")).andReturn(), "XSRF-TOKEN");
  }

  MockHttpServletRequestBuilder as(UUID user, MockHttpServletRequestBuilder request) {
    Cookie access = new Cookie("HA_ACCESS", tokens.issue(user, Instant.now().plus(10, ChronoUnit.MINUTES)));
    return request.cookie(access, new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf);
  }

  ResultActions send(UUID user, MockHttpServletRequestBuilder request, String body) throws Exception {
    return mvc.perform(as(user, request).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  String create(String body) throws Exception {
    String response =
        send(SEEDED_USER, post(REMINDERS), body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return json.readTree(response).at("/data/id").asText();
  }

  // The worker and the cleanup job are off in this class; the tests below run them by hand with a
  // fixed clock and a fake channel, after the HTTP calls that set the reminders up.
  static final ReminderProperties WORKER =
      new ReminderProperties(true, Duration.ofMinutes(1), Duration.ofMinutes(10), 3, 20, "reminders@example.invalid");
  /** A 07:00 UTC occurrence; reminders below use "timezone":"UTC". */
  static final Instant DUE = Instant.parse("2026-11-16T07:00:00Z");

  ReminderDispatcher.Result runWorker(ReminderDispatcherTest.FakeChannel channel, Instant now) {
    return new ReminderDispatcher(reminders, consents, List.of(channel), Clock.fixed(now, ZoneOffset.UTC), WORKER)
        .runOnce();
  }

  LifestyleRetention.Result runRetention(Instant now) {
    return new LifestyleRetention(retentionRepository, retentionProperties, Clock.fixed(now, ZoneOffset.UTC)).runOnce();
  }

  /** The worker only sends occurrences after the last save, so move that save to just before {@code due}. */
  void savedJustBefore(UUID id, Instant due) {
    assertThat(jdbc.sql("UPDATE lifestyle.reminders SET updated_at = ? WHERE id = ?")
            .params(due.atOffset(ZoneOffset.UTC).minusMinutes(1), id)
            .update())
        .isEqualTo(1);
  }

  /** A signed-in USER who accepted health-data processing, like the seeded user. */
  void regularUserWithConsent(UUID user) {
    jdbc.sql("INSERT INTO accounts.user_roles (user_id, role) VALUES (?, 'USER') ON CONFLICT DO NOTHING")
        .param(user)
        .update();
    jdbc.sql(
            """
            INSERT INTO accounts.consents
              (user_id, terms_version, adult_confirmed, accepted_at,
               health_data_processing_accepted, non_diagnostic_acknowledged)
            VALUES (?, 'test-consent', true, now(), true, true)
            ON CONFLICT DO NOTHING
            """)
        .param(user)
        .update();
  }

  ResultActions choosePlan(String templateId) throws Exception {
    return send(SEEDED_USER, put(PLAN), "{\"templateId\":\"" + templateId + "\"}").andExpect(status().isOk());
  }

  List<Map<String, Object>> deliveryRows(UUID id) {
    return jdbc.sql(
            """
            SELECT id, status, attempt_count, last_error_code, updated_at
            FROM lifestyle.reminder_deliveries WHERE reminder_id = ?
            """)
        .param(id)
        .query()
        .listOfRows();
  }

  Map<String, Object> stored(String id) {
    return jdbc.sql("SELECT enabled, disabled_reason, activity_id, content FROM lifestyle.reminders WHERE id = ?::uuid")
        .param(id)
        .query()
        .singleRow();
  }

  // Test round 1 (#146), cases 14-04, 14-06, 14-10, 14-11, 14-12 and 14-03, kept as regression tests.

  @Test
  @Transactional
  void turningOffAfterAFailedSendStopsTheRetry() throws Exception {
    UUID id = UUID.fromString(create("{\"content\":\"[TEST] Tắt sau lỗi\",\"localTime\":\"07:00\",\"timezone\":\"UTC\"}"));
    savedJustBefore(id, DUE);
    var channel = new ReminderDispatcherTest.FakeChannel();
    channel.failing.add(SEEDED_USER);
    assertThat(runWorker(channel, DUE)).isEqualTo(new ReminderDispatcher.Result(0, 1));
    var firstAttempt = deliveryRows(id);
    assertThat(firstAttempt)
        .singleElement()
        .satisfies(row -> assertThat(row)
            .containsEntry("status", "FAILED")
            .containsEntry("attempt_count", 1)
            .containsEntry("last_error_code", "TEST_FAILURE"));

    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":false}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false))
        .andExpect(jsonPath("$.data.nextSendAt").doesNotExist());
    channel.failing.clear();
    assertThat(runWorker(channel, DUE.plusSeconds(60))).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(channel.sent).isEmpty();
    assertThat(deliveryRows(id)).isEqualTo(firstAttempt);
  }

  @Test
  @Transactional
  void deletingAfterAFailedSendRemovesTheRetry() throws Exception {
    UUID id = UUID.fromString(create("{\"content\":\"[TEST] Xóa sau lỗi\",\"localTime\":\"07:00\",\"timezone\":\"UTC\"}"));
    savedJustBefore(id, DUE);
    var channel = new ReminderDispatcherTest.FakeChannel();
    channel.failing.add(SEEDED_USER);
    assertThat(runWorker(channel, DUE)).isEqualTo(new ReminderDispatcher.Result(0, 1));

    mvc.perform(as(SEEDED_USER, delete(REMINDERS + "/" + id))).andExpect(status().isOk());
    channel.failing.clear();
    assertThat(runWorker(channel, DUE.plusSeconds(60))).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(channel.sent).isEmpty();
    assertThat(deliveryRows(id)).isEmpty();
  }

  @Test
  @Transactional
  void aReminderWithoutActivityIsStillSentAfterASwitch() throws Exception {
    choosePlan(LifestylePlanApiTest.SLEEP);
    UUID id = UUID.fromString(create("{\"content\":\"[TEST] Uống nước\",\"localTime\":\"07:00\",\"timezone\":\"UTC\"}"));
    choosePlan(LifestylePlanApiTest.MAINTAIN).andExpect(jsonPath("$.data.remindersTurnedOff").value(0));
    savedJustBefore(id, DUE);

    var channel = new ReminderDispatcherTest.FakeChannel();
    assertThat(runWorker(channel, DUE)).isEqualTo(new ReminderDispatcher.Result(1, 0));
    assertThat(channel.sent).containsExactly(SEEDED_USER);
    assertThat(deliveryRows(id))
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("status", "SENT").containsEntry("attempt_count", 1));
  }

  @Test
  @Transactional
  void unlinkingTheActivityKeepsContentAndState() throws Exception {
    choosePlan(LifestylePlanApiTest.SLEEP);
    String id = create("{\"activityId\":\"sleep-time\",\"content\":\"[TEST] Giữ nội dung\",\"localTime\":\"22:00\"}");
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"activityId\":null}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.activityId").doesNotExist())
        .andExpect(jsonPath("$.data.content").value("[TEST] Giữ nội dung"))
        .andExpect(jsonPath("$.data.enabled").value(true))
        .andExpect(jsonPath("$.data.nextSendAt").isNotEmpty());
    assertThat(stored(id))
        .containsEntry("activity_id", null)
        .containsEntry("content", "[TEST] Giữ nội dung")
        .containsEntry("enabled", true);
  }

  @Test
  @Transactional
  void theLimitIsPerUserAndCountsRemindersTheUserTurnedOff() throws Exception {
    regularUserWithConsent(OTHER_USER);
    int before = reminders.count(SEEDED_USER);
    int otherBefore = reminders.count(OTHER_USER);
    assertThat(jdbc.sql(
                """
                INSERT INTO lifestyle.reminders (id, user_id, content, local_time, timezone, channel, enabled)
                SELECT gen_random_uuid(), ?, '[TEST] Đã tắt', TIME '05:00', 'UTC', 'EMAIL', false
                FROM generate_series(1, ?)
                """)
            .params(SEEDED_USER, 20 - before)
            .update())
        .isEqualTo(20 - before);
    String request = "{\"content\":\"[TEST] Nhắc thứ 21\",\"localTime\":\"22:00\",\"enabled\":false}";

    send(SEEDED_USER, post(REMINDERS), request)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("REMINDER_LIMIT_REACHED"));
    send(OTHER_USER, post(REMINDERS), request).andExpect(status().isCreated());
    assertThat(reminders.count(SEEDED_USER)).isEqualTo(20);
    assertThat(reminders.count(OTHER_USER)).isEqualTo(otherBefore + 1);
  }

  @Test
  @Transactional
  void aReminderOfAnActivityTheNewPlanLacksIsNotSent() throws Exception {
    choosePlan(LifestylePlanApiTest.SLEEP);
    String id = create("{\"activityId\":\"sleep-time\",\"localTime\":\"07:00\",\"timezone\":\"UTC\"}");
    choosePlan(LifestylePlanApiTest.MAINTAIN).andExpect(jsonPath("$.data.remindersTurnedOff").value(1));
    mvc.perform(as(SEEDED_USER, get(REMINDERS)))
        .andExpect(jsonPath("$.data.reminders[?(@.id == '" + id + "')].disabledReason").value("PLAN_SWITCH"));
    savedJustBefore(UUID.fromString(id), DUE);

    var channel = new ReminderDispatcherTest.FakeChannel();
    assertThat(runWorker(channel, DUE)).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(channel.sent).isEmpty();
  }

  // Test round 2 (#166): reminders paused by a plan switch (#158) and the cleanup job (#160), over
  // HTTP, with the worker and the job run by hand.

  @Test
  @Transactional
  void aPausedReminderIsOfferedBackAndSentOnlyOnceTheUserTurnsItOn() throws Exception {
    choosePlan(LifestylePlanApiTest.SLEEP);
    String id = create("{\"activityId\":\"sleep-time\",\"localTime\":\"07:00\",\"timezone\":\"UTC\"}");
    choosePlan(LifestylePlanApiTest.MAINTAIN);
    choosePlan(LifestylePlanApiTest.SLEEP)
        .andExpect(jsonPath("$.data.remindersTurnedOff").value(0))
        .andExpect(jsonPath("$.data.restorableReminderIds", hasItem(id)));
    // Offered, not turned on: the worker still sends nothing.
    assertThat(stored(id)).containsEntry("enabled", false).containsEntry("disabled_reason", "PLAN_SWITCH");
    savedJustBefore(UUID.fromString(id), DUE);
    var channel = new ReminderDispatcherTest.FakeChannel();
    assertThat(runWorker(channel, DUE)).isEqualTo(new ReminderDispatcher.Result(0, 0));

    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":true}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(true))
        .andExpect(jsonPath("$.data.disabledReason").doesNotExist())
        .andExpect(jsonPath("$.data.nextSendAt").isNotEmpty());
    Instant nextDay = DUE.plus(Duration.ofDays(1));
    savedJustBefore(UUID.fromString(id), nextDay);
    assertThat(runWorker(channel, nextDay)).isEqualTo(new ReminderDispatcher.Result(1, 0));
    assertThat(runWorker(channel, nextDay.plusSeconds(60))).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(channel.sent).containsExactly(SEEDED_USER);
  }

  @Test
  @Transactional
  void turningOnAPausedReminderAtTheLimitIsRefusedAndNothingChanges() throws Exception {
    choosePlan(LifestylePlanApiTest.SLEEP);
    String id = create("{\"activityId\":\"sleep-time\",\"localTime\":\"07:00\",\"timezone\":\"UTC\"}");
    choosePlan(LifestylePlanApiTest.MAINTAIN);
    int counted = reminders.count(SEEDED_USER); // the paused one is not counted
    jdbc.sql(
            """
            INSERT INTO lifestyle.reminders (id, user_id, content, local_time, timezone, channel, enabled)
            SELECT gen_random_uuid(), ?, '[TEST] Đã tắt', TIME '05:00', 'UTC', 'EMAIL', false
            FROM generate_series(1, ?)
            """)
        .params(SEEDED_USER, 20 - counted)
        .update();

    // Its activity is not in the new plan, so it can only come back unlinked; at 20 it cannot come back.
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":true}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_IN_PLAN"));
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":true,\"activityId\":null}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("REMINDER_LIMIT_REACHED"));
    assertThat(stored(id))
        .containsEntry("enabled", false)
        .containsEntry("disabled_reason", "PLAN_SWITCH")
        .containsEntry("activity_id", "sleep-time");
    assertThat(reminders.count(SEEDED_USER)).isEqualTo(20);
  }

  @Test
  @Transactional
  void editingAPausedReminderKeepsItPaused() throws Exception {
    choosePlan(LifestylePlanApiTest.SLEEP);
    String id = create("{\"activityId\":\"sleep-time\",\"localTime\":\"07:00\",\"timezone\":\"UTC\"}");
    choosePlan(LifestylePlanApiTest.MAINTAIN);

    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"content\":\"[TEST] Nội dung mới\",\"localTime\":\"06:30\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").value("[TEST] Nội dung mới"))
        .andExpect(jsonPath("$.data.enabled").value(false))
        .andExpect(jsonPath("$.data.disabledReason").value("PLAN_SWITCH"))
        .andExpect(jsonPath("$.data.nextSendAt").doesNotExist());
    Instant due = Instant.parse("2026-11-16T06:30:00Z");
    savedJustBefore(UUID.fromString(id), due);
    var channel = new ReminderDispatcherTest.FakeChannel();
    assertThat(runWorker(channel, due)).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(channel.sent).isEmpty();
  }

  @Test
  @Transactional
  void theCleanupJobRemovesOnlyRemindersPausedByASwitch() throws Exception {
    choosePlan(LifestylePlanApiTest.SLEEP);
    String paused = create("{\"activityId\":\"sleep-time\",\"localTime\":\"07:00\",\"timezone\":\"UTC\"}");
    String pausedThenOff = create("{\"activityId\":\"no-screen\",\"localTime\":\"21:30\",\"timezone\":\"UTC\"}");
    String offByUser =
        create("{\"content\":\"[TEST] Người dùng tự tắt\",\"localTime\":\"08:00\",\"timezone\":\"UTC\",\"enabled\":false}");
    choosePlan(LifestylePlanApiTest.MAINTAIN).andExpect(jsonPath("$.data.remindersTurnedOff").value(2));
    // Decided on #60: turning a paused reminder off by hand keeps it paused (reason PLAN_SWITCH).
    send(SEEDED_USER, patch(REMINDERS + "/" + pausedThenOff), "{\"enabled\":false}").andExpect(status().isOk());
    assertThat(stored(pausedThenOff)).containsEntry("disabled_reason", "PLAN_SWITCH");

    Instant now = Instant.now();
    Duration period = retentionProperties.pausedReminders();
    runRetention(now.plus(period).minus(Duration.ofDays(1)));
    assertThat(stored(paused)).containsEntry("disabled_reason", "PLAN_SWITCH"); // not yet

    runRetention(now.plus(period).plus(Duration.ofDays(1)));
    mvc.perform(as(SEEDED_USER, get(REMINDERS)))
        .andExpect(jsonPath("$.data.reminders[*].id", not(hasItem(paused))))
        .andExpect(jsonPath("$.data.reminders[*].id", not(hasItem(pausedThenOff))))
        .andExpect(jsonPath("$.data.reminders[*].id", hasItem(offByUser)));
    // Switching back has nothing left to offer.
    choosePlan(LifestylePlanApiTest.SLEEP).andExpect(jsonPath("$.data.restorableReminderIds").isEmpty());
  }

  @Test
  void routesNeedASessionAndWritesNeedCsrf() throws Exception {
    mvc.perform(get(REMINDERS)).andExpect(status().isUnauthorized());
    Cookie access = new Cookie("HA_ACCESS", tokens.issue(SEEDED_USER, Instant.now().plus(10, ChronoUnit.MINUTES)));
    mvc.perform(post(REMINDERS).cookie(access).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
  }

  @Test
  @Transactional
  void withoutConsentTheOwnerCanOnlySeeTurnOffAndDelete() throws Exception {
    String id = create("{\"content\":\"[TEST] Uống nước\",\"localTime\":\"07:30\"}");
    jdbc.sql("UPDATE accounts.consents SET health_data_processing_accepted = false WHERE user_id = ?")
        .param(SEEDED_USER)
        .update();
    int before = count();

    // Creating, turning back on or changing anything else still needs consent.
    send(SEEDED_USER, post(REMINDERS), "{\"content\":\"Uống nước\",\"localTime\":\"07:30\"}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("HEALTH_CONSENT_REQUIRED"));
    send(SEEDED_USER, patch(REMINDERS + "/" + SEEDED_REMINDER), "{\"enabled\":true}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("HEALTH_CONSENT_REQUIRED"));
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":false,\"localTime\":\"08:00\"}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("HEALTH_CONSENT_REQUIRED"));
    assertThat(count()).isEqualTo(before);

    mvc.perform(as(SEEDED_USER, get(REMINDERS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reminders[*].id", hasItem(id)));
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":false}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false))
        .andExpect(jsonPath("$.data.localTime").value("07:30"));
    mvc.perform(as(SEEDED_USER, delete(REMINDERS + "/" + id))).andExpect(status().isOk());
    assertThat(count()).isEqualTo(before - 1);
  }

  @Test
  void createListChangeTurnOffAndDelete() throws Exception {
    String id = create("{\"content\":\" Uống một ly nước \",\"localTime\":\"07:30\",\"daysOfWeek\":[5,1,1,3]}");

    mvc.perform(as(SEEDED_USER, get(REMINDERS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.availableChannels").value(contains("EMAIL")))
        .andExpect(jsonPath("$.data.reminders[?(@.id == '" + id + "')].content").value(contains("Uống một ly nước")))
        .andExpect(jsonPath("$.data.reminders[?(@.id == '" + id + "')].daysOfWeek[*]").value(contains(1, 3, 5)))
        .andExpect(jsonPath("$.data.reminders[?(@.id == '" + id + "')].timezone").value(contains("Asia/Ho_Chi_Minh")))
        .andExpect(jsonPath("$.data.reminders[?(@.id == '" + id + "')].channel").value(contains("EMAIL")))
        .andExpect(jsonPath("$.data.reminders[?(@.id == '" + id + "')].enabled").value(contains(true)))
        .andExpect(jsonPath("$.data.reminders[*].id").value(hasItem(SEEDED_REMINDER)));

    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"localTime\":\"21:00\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.localTime").value("21:00"))
        .andExpect(jsonPath("$.data.content").value("Uống một ly nước"))
        .andExpect(jsonPath("$.data.nextSendAt").isNotEmpty());
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":false}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false))
        .andExpect(jsonPath("$.data.nextSendAt").doesNotExist());

    mvc.perform(as(SEEDED_USER, delete(REMINDERS + "/" + id))).andExpect(status().isOk());
    mvc.perform(as(SEEDED_USER, get(REMINDERS)))
        .andExpect(jsonPath("$.data.reminders[*].id").value(not(hasItem(id))));
    mvc.perform(as(SEEDED_USER, delete(REMINDERS + "/" + id)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("REMINDER_NOT_FOUND"));
  }

  @Test
  void anotherUserCannotSeeChangeOrDeleteIt() throws Exception {
    regularUserWithConsent(OTHER_USER);
    String id = create("{\"content\":\"[TEST] Đi bộ\",\"localTime\":\"18:00\"}");
    mvc.perform(as(OTHER_USER, get(REMINDERS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reminders[*].id").value(not(hasItem(id))));
    send(OTHER_USER, patch(REMINDERS + "/" + id), "{\"enabled\":false}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("REMINDER_NOT_FOUND"));
    mvc.perform(as(OTHER_USER, delete(REMINDERS + "/" + id))).andExpect(status().isNotFound());
    assertThat(
            jdbc.sql("SELECT enabled FROM lifestyle.reminders WHERE id = ?::uuid")
                .param(id)
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  @Test
  void invalidInputIsRejectedAndNothingIsSaved() throws Exception {
    int before = count();
    for (String body :
        new String[] {
          "{\"content\":\"Uống nước\",\"localTime\":\"7:00\"}",
          "{\"content\":\"Uống nước\",\"localTime\":\"24:00\"}",
          "{\"content\":\"Uống nước\",\"localTime\":\"07:00:00\"}",
          "{\"content\":\"Uống nước\"}",
          "{\"content\":\"   \",\"localTime\":\"07:00\"}",
          "{\"content\":\"" + "a".repeat(201) + "\",\"localTime\":\"07:00\"}",
          "{\"content\":\"Uống\\u0007 nước\",\"localTime\":\"07:00\"}",
          "{\"content\":\"Uống nước\",\"localTime\":\"07:00\",\"daysOfWeek\":[]}",
          "{\"content\":\"Uống nước\",\"localTime\":\"07:00\",\"daysOfWeek\":[0,8]}",
          "{\"content\":\"Uống nước\",\"localTime\":\"07:00\",\"timezone\":\"Mars/Base\"}",
          "{\"content\":\"Uống nước\",\"localTime\":\"07:00\",\"channel\":\"SMS\"}",
          "{\"content\":\"Uống nước\",\"localTime\":\"07:00\",\"enabled\":\"yes\"}",
          "{\"content\":\"Uống nước\",\"localTime\":\"07:00\",\"userId\":\"" + OTHER_USER + "\"}",
          "[]"
        }) {
      send(SEEDED_USER, post(REMINDERS), body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("INVALID_REMINDER"));
    }
    assertThat(count()).isEqualTo(before);
  }

  @Test
  void webPushIsNotAvailableYetButMayBeSavedOff() throws Exception {
    send(SEEDED_USER, post(REMINDERS), "{\"content\":\"Ngủ sớm\",\"localTime\":\"22:00\",\"channel\":\"WEB_PUSH\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("REMINDER_CHANNEL_UNAVAILABLE"));
    String id = create("{\"content\":\"Ngủ sớm\",\"localTime\":\"22:00\",\"channel\":\"WEB_PUSH\",\"enabled\":false}");
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":true}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("REMINDER_CHANNEL_UNAVAILABLE"));
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":true,\"channel\":\"EMAIL\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.channel").value("EMAIL"));
  }

  @Test
  void aReminderCanFollowAnActivityOfTheActivePlan() throws Exception {
    // The seeded plan's template has one activity, "demo".
    send(SEEDED_USER, post(REMINDERS), "{\"activityId\":\"demo\",\"localTime\":\"06:00\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.activityId").value("demo"))
        .andExpect(jsonPath("$.data.content").value("Hoạt động giả lập"));
    send(SEEDED_USER, post(REMINDERS), "{\"activityId\":\"swim\",\"localTime\":\"06:00\"}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_IN_PLAN"));
  }

  /** After a plan switch turned it off, a reminder of an old activity comes back only relinked or unlinked. */
  @Test
  @Transactional
  void aReminderOfAnActivityNoLongerInThePlanIsTurnedOnOnlyRelinkedOrUnlinked() throws Exception {
    String id = create("{\"activityId\":\"demo\",\"localTime\":\"06:00\"}");
    // What the plan switch leaves behind: off, still pointing at the old plan's activity.
    jdbc.sql("UPDATE lifestyle.reminders SET enabled = false, activity_id = 'old-plan-walk' WHERE id = ?::uuid")
        .param(id)
        .update();

    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":true}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_IN_PLAN"));
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"content\":\"[TEST] Đi bộ\"}")
        .andExpect(status().isOk()); // still off: no check needed
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":true,\"activityId\":\"demo\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(true))
        .andExpect(jsonPath("$.data.activityId").value("demo"));
    jdbc.sql("UPDATE lifestyle.reminders SET enabled = false, activity_id = 'old-plan-walk' WHERE id = ?::uuid")
        .param(id)
        .update();
    send(SEEDED_USER, patch(REMINDERS + "/" + id), "{\"enabled\":true,\"activityId\":null}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(true))
        .andExpect(jsonPath("$.data.activityId").doesNotExist());
  }

  int count() {
    return jdbc.sql("SELECT count(*) FROM lifestyle.reminders").query(Integer.class).single();
  }
}
