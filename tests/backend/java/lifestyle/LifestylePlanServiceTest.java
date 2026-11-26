package lifestyle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import accounts.persistence.JdbcConsentQuery;
import health.JdbcHealthProfileQuery;
import lifestyle.ReminderRepository.Fields;
import lifestyle.TemplateCatalog.TemplateSummary;
import shared.web.ApiException;
import support.TestDatabase;

/** UC-12 against a migrated database. Synthetic users and [TEST] templates only. */
class LifestylePlanServiceTest {
  static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
  static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
  static final LocalDate TODAY = LocalDate.parse("2026-11-16");
  static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000701");
  static final UUID OTHER_USER = UUID.fromString("00000000-0000-4000-8000-000000000702");
  static final UUID SLEEP = UUID.fromString("00000000-0000-4000-8000-000000000710");
  static final UUID SLEEP_ALT = UUID.fromString("00000000-0000-4000-8000-000000000711");
  static final UUID PEANUT = UUID.fromString("00000000-0000-4000-8000-000000000712");
  static final UUID NEEDS_PROFILE = UUID.fromString("00000000-0000-4000-8000-000000000713");
  static final UUID DRAFT = UUID.fromString("00000000-0000-4000-8000-000000000714");
  static final UUID RETIRED = UUID.fromString("00000000-0000-4000-8000-000000000715");
  static final UUID SYNTHETIC = UUID.fromString("00000000-0000-4000-8000-000000000716");
  static final String ACTIVITIES =
      "[{\"id\":\"walk\",\"title\":\"[TEST] Đi bộ\",\"description\":\"[TEST] 30 phút\"},{\"id\":\"sleep\",\"title\":\"[TEST] Ngủ sớm\"}]";

  JdbcClient jdbc;
  LifestyleRepository repository;
  ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void freshDatabase() {
    DataSource dataSource = TestDatabase.fresh();
    jdbc = JdbcClient.create(dataSource);
    repository =
        new LifestyleRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    user(USER, "uc12-a@example.invalid");
    user(OTHER_USER, "uc12-b@example.invalid");
    consent(USER, true);
    consent(OTHER_USER, true);
    profile(USER, "{}");
    template(SLEEP, "[TEST] Ngủ đúng giờ", "SLEEP", "{}", "APPROVED", false, false);
    template(SLEEP_ALT, "[TEST] Ngủ trưa ngắn", "SLEEP", "{}", "APPROVED", false, false);
    template(PEANUT, "[TEST] Bữa phụ đậu phộng", "GAIN", "{\" Đậu Phộng \"}", "APPROVED", false, false);
    template(NEEDS_PROFILE, "[TEST] Giảm cân theo hồ sơ", "LOSE", "{}", "APPROVED", true, false);
    template(DRAFT, "[TEST] Mẫu nháp", "SLEEP", "{}", "DRAFT", false, false);
    template(RETIRED, "[TEST] Mẫu đã ngừng", "SLEEP", "{}", "RETIRED", false, false);
    template(SYNTHETIC, "[TEST] Mẫu giả lập", "SLEEP", "{}", "APPROVED", false, true);
  }

  void user(UUID id, String email) {
    jdbc.sql("INSERT INTO accounts.users (id, email, display_name, is_synthetic) VALUES (?, ?, '[TEST]', true)")
        .params(id, email)
        .update();
  }

  void consent(UUID id, boolean healthData) {
    jdbc.sql(
            """
            INSERT INTO accounts.consents
              (user_id, terms_version, adult_confirmed, accepted_at, health_data_processing_accepted)
            VALUES (?, 'test-v1', true, now(), ?)
            """)
        .params(id, healthData)
        .update();
  }

  void profile(UUID id, String allergies) {
    jdbc.sql("INSERT INTO health.profiles (user_id, food_allergies) VALUES (?, ?::text[])")
        .params(id, allergies)
        .update();
  }

  void template(
      UUID id, String title, String goal, String allergens, String status, boolean requiresProfile, boolean synthetic) {
    jdbc.sql(
            """
            INSERT INTO lifestyle.templates
              (id, title, goal, activities, excluded_allergens, review_status, source_url, is_synthetic,
               summary, duration_days, not_suitable_for, requires_profile)
            VALUES (?, ?, ?, ?::jsonb, ?::text[], ?, 'https://example.invalid/template', ?,
                    '[TEST] Mô tả', 14, '{"[TEST] Người làm ca đêm"}', ?)
            """)
        .params(id, title, goal, ACTIVITIES, allergens, status, synthetic, requiresProfile)
        .update();
  }

  LifestylePlanService service(boolean includeSynthetic) {
    return new LifestylePlanService(
        repository,
        json,
        Clock.fixed(NOW, VIETNAM),
        new JdbcHealthProfileQuery(jdbc),
        new JdbcConsentQuery(jdbc),
        includeSynthetic);
  }

  LifestylePlanService service() {
    return service(false);
  }

  JsonNode body(String templateId) {
    return json.createObjectNode().put("templateId", templateId);
  }

  int plans(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM lifestyle.plans WHERE user_id = ?").param(userId).query(Integer.class).single();
  }

  int activePlans(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM lifestyle.plans WHERE user_id = ? AND ended_on IS NULL")
        .param(userId)
        .query(Integer.class)
        .single();
  }

  static void fails(ThrowingCallable call, HttpStatus status, String code) {
    assertThatThrownBy(call)
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).status()).isEqualTo(status);
              assertThat(((ApiException) e).code()).isEqualTo(code);
            });
  }

  @Test
  void listsOnlyApprovedTemplatesOfTheGoal() {
    TemplateCatalog sleep = service().templates(USER, "sleep");
    assertThat(sleep.goal()).isEqualTo(LifestyleGoal.SLEEP);
    assertThat(sleep.templates()).extracting(TemplateSummary::id)
        .containsExactlyInAnyOrder(SLEEP_ALT.toString(), SLEEP.toString());
    assertThat(sleep.profileMissing()).isFalse();
    assertThat(sleep.message()).isNull();

    TemplateSummary first =
        sleep.templates().stream().filter(t -> t.id().equals(SLEEP.toString())).findFirst().orElseThrow();
    assertThat(first.activityCount()).isEqualTo(2);
    assertThat(first.durationDays()).isEqualTo(14);
    assertThat(first.notSuitableFor()).containsExactly("[TEST] Người làm ca đêm");
    assertThat(first.sourceUrl()).isEqualTo("https://example.invalid/template");

    assertThat(service().templates(USER, null).templates()).extracting(TemplateSummary::id)
        .doesNotContain(DRAFT.toString(), RETIRED.toString(), SYNTHETIC.toString());
  }

  @Test
  void syntheticTemplatesAreOfferedOnlyWhenSyntheticSeedingIsOn() {
    assertThat(service(true).templates(USER, "SLEEP").templates()).extracting(TemplateSummary::id)
        .contains(SYNTHETIC.toString());
    fails(() -> service(false).preview(USER, SYNTHETIC.toString()), HttpStatus.NOT_FOUND, "LIFESTYLE_TEMPLATE_NOT_FOUND");
  }

  @Test
  void aTemplateWithADeclaredAllergenIsNeverOfferedNorApplied() {
    jdbc.sql("UPDATE health.profiles SET food_allergies = '{\"đậu phộng\"}' WHERE user_id = ?").param(USER).update();

    assertThat(service().templates(USER, "GAIN").templates()).isEmpty();
    assertThat(service().templates(USER, null).templates()).extracting(TemplateSummary::id)
        .doesNotContain(PEANUT.toString());
    fails(() -> service().preview(USER, PEANUT.toString()), HttpStatus.CONFLICT, "TEMPLATE_HAS_ALLERGEN");
    fails(() -> service().apply(USER, body(PEANUT.toString())), HttpStatus.CONFLICT, "TEMPLATE_HAS_ALLERGEN");
    assertThat(plans(USER)).isZero();
  }

  @Test
  void withoutAProfileOnlyTemplatesThatAllowItAreOffered() {
    TemplateCatalog catalog = service().templates(OTHER_USER, null);
    assertThat(catalog.profileMissing()).isTrue();
    assertThat(catalog.templates()).extracting(TemplateSummary::id)
        .contains(SLEEP.toString(), PEANUT.toString())
        .doesNotContain(NEEDS_PROFILE.toString());
    fails(() -> service().apply(OTHER_USER, body(NEEDS_PROFILE.toString())), HttpStatus.CONFLICT, "HEALTH_PROFILE_REQUIRED");

    assertThat(service().apply(OTHER_USER, body(SLEEP.toString())).changed()).isTrue();
    assertThat(service().templates(USER, "LOSE").templates()).extracting(TemplateSummary::id)
        .containsExactly(NEEDS_PROFILE.toString());
  }

  @Test
  void noSuitableTemplateGivesAMessageAndNoPlan() {
    jdbc.sql("UPDATE health.profiles SET food_allergies = '{\"đậu phộng\"}' WHERE user_id = ?").param(USER).update();
    TemplateCatalog catalog = service().templates(USER, "GAIN");
    assertThat(catalog.templates()).isEmpty();
    assertThat(catalog.message()).isEqualTo(LifestylePlanService.EMPTY);
    assertThat(plans(USER)).isZero();
  }

  @Test
  void healthDataConsentIsRequired() {
    UUID noConsent = UUID.fromString("00000000-0000-4000-8000-000000000703");
    user(noConsent, "uc12-c@example.invalid");
    fails(() -> service().templates(noConsent, null), HttpStatus.FORBIDDEN, "HEALTH_CONSENT_REQUIRED");
    fails(() -> service().preview(noConsent, SLEEP.toString()), HttpStatus.FORBIDDEN, "HEALTH_CONSENT_REQUIRED");
    fails(() -> service().apply(noConsent, body(SLEEP.toString())), HttpStatus.FORBIDDEN, "HEALTH_CONSENT_REQUIRED");

    // The latest consent decides: accepting later lets the user choose.
    jdbc.sql(
            """
            INSERT INTO accounts.consents (user_id, terms_version, adult_confirmed, accepted_at, health_data_processing_accepted)
            VALUES (?, 'old', true, now() - interval '1 day', true), (?, 'new', true, now(), false)
            """)
        .params(noConsent, noConsent)
        .update();
    fails(() -> service().templates(noConsent, null), HttpStatus.FORBIDDEN, "HEALTH_CONSENT_REQUIRED");
    assertThat(plans(noConsent)).isZero();
  }

  @Test
  void invalidInputAndUnavailableTemplatesAreRejected() {
    fails(() -> service().templates(USER, "SWIM"), HttpStatus.BAD_REQUEST, "INVALID_GOAL");
    fails(() -> service().preview(USER, "not-a-uuid"), HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE_ID");
    fails(() -> service().apply(USER, null), HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE_ID");
    fails(
        () -> service().apply(USER, json.createObjectNode().put("templateId", 42)),
        HttpStatus.BAD_REQUEST,
        "INVALID_TEMPLATE_ID");
    for (UUID unavailable : new UUID[] {DRAFT, RETIRED, UUID.fromString("00000000-0000-4000-8000-000000000799")}) {
      fails(() -> service().preview(USER, unavailable.toString()), HttpStatus.NOT_FOUND, "LIFESTYLE_TEMPLATE_NOT_FOUND");
      fails(() -> service().apply(USER, body(unavailable.toString())), HttpStatus.NOT_FOUND, "LIFESTYLE_TEMPLATE_NOT_FOUND");
    }
    assertThat(plans(USER)).isZero();
  }

  @Test
  void previewShowsTheScheduleAndChangesNothing() {
    TemplatePreview preview = service().preview(USER, SLEEP.toString());
    assertThat(preview.activities())
        .containsExactly(
            new PlanActivity("walk", "[TEST] Đi bộ", "[TEST] 30 phút"), new PlanActivity("sleep", "[TEST] Ngủ sớm", null));
    assertThat(preview.advice()).isEqualTo(LifestylePlanService.ADVICE);
    assertThat(preview.current()).isFalse();
    assertThat(plans(USER)).isZero();

    service().apply(USER, body(SLEEP.toString()));
    assertThat(service().preview(USER, SLEEP.toString()).current()).isTrue();
    // A4: previewing another template keeps the current plan.
    assertThat(service().preview(USER, SLEEP_ALT.toString()).current()).isFalse();
    assertThat(repository.findActivePlan(USER).orElseThrow().templateId()).isEqualTo(SLEEP);
  }

  @Test
  void applyingAndSwitchingKeepsOneActivePlanAndTheHistory() {
    AppliedPlan first = service().apply(USER, body(SLEEP.toString()));
    assertThat(first.changed()).isTrue();
    assertThat(first.previousPlanId()).isNull();
    assertThat(first.templateId()).isEqualTo(SLEEP.toString());
    assertThat(first.startedOn()).isEqualTo(TODAY);

    AppliedPlan again = service().apply(USER, body(SLEEP.toString()));
    assertThat(again.changed()).isFalse();
    assertThat(again.planId()).isEqualTo(first.planId());
    assertThat(plans(USER)).isEqualTo(1);

    jdbc.sql("INSERT INTO lifestyle.completions (plan_id, activity_id, completed_on) VALUES (?::uuid, 'walk', ?)")
        .params(first.planId(), TODAY)
        .update();
    AppliedPlan switched = service().apply(USER, body(SLEEP_ALT.toString()));
    assertThat(switched.changed()).isTrue();
    assertThat(switched.previousPlanId()).isEqualTo(first.planId());
    assertThat(switched.planId()).isNotEqualTo(first.planId());
    assertThat(plans(USER)).isEqualTo(2);
    assertThat(activePlans(USER)).isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT ended_on FROM lifestyle.plans WHERE id = ?::uuid")
                .param(first.planId())
                .query(LocalDate.class)
                .single())
        .isEqualTo(TODAY);
    assertThat(
            jdbc.sql("SELECT count(*) FROM lifestyle.completions WHERE plan_id = ?::uuid")
                .param(first.planId())
                .query(Integer.class)
                .single())
        .isEqualTo(1);

    // UC-13 now reads the new plan.
    TodayPlan today =
        new LifestyleService(repository, json, Clock.fixed(NOW, VIETNAM), userId -> true, 100)
            .today(USER);
    assertThat(today.planId()).isEqualTo(switched.planId());
    assertThat(today.activities()).noneMatch(TodayPlan.ActivityToday::completed);
  }

  /** UC-14 rule for test case 14-03: switching plans stops reminders of activities the new plan lacks. */
  @Test
  void switchingPlansTurnsOffRemindersOfActivitiesTheNewPlanLacks() {
    UUID swimAndSleep = UUID.fromString("00000000-0000-4000-8000-000000000717");
    jdbc.sql(
            """
            INSERT INTO lifestyle.templates
              (id, title, goal, activities, review_status, source_url, is_synthetic, summary, duration_days)
            VALUES (?, '[TEST] Bơi và ngủ sớm', 'SLEEP',
                    '[{"id":"swim","title":"[TEST] Bơi"},{"id":"sleep","title":"[TEST] Ngủ sớm"}]'::jsonb,
                    'APPROVED', 'https://example.invalid/template', false, '[TEST] Mô tả', 14)
            """)
        .param(swimAndSleep)
        .update();
    service().apply(USER, body(SLEEP.toString())); // activities walk, sleep
    ReminderRepository reminders = new ReminderRepository(jdbc);
    Instant saved = NOW.minus(Duration.ofHours(1));
    UUID walk = UUID.randomUUID();
    UUID sleep = UUID.randomUUID();
    UUID standalone = UUID.randomUUID();
    UUID walkOff = UUID.randomUUID();
    UUID otherUsersWalk = UUID.randomUUID();
    reminders.insert(walk, USER, reminder("walk", true), saved);
    reminders.insert(sleep, USER, reminder("sleep", true), saved);
    reminders.insert(standalone, USER, reminder(null, true), saved);
    reminders.insert(walkOff, USER, reminder("walk", false), saved);
    reminders.insert(otherUsersWalk, OTHER_USER, reminder("walk", true), saved);
    // Sent before the switch: stays as history.
    OffsetDateTime savedAt = OffsetDateTime.ofInstant(saved, ZoneOffset.UTC);
    jdbc.sql(
            """
            INSERT INTO lifestyle.reminder_deliveries
              (id, reminder_id, scheduled_for, channel, status, attempt_count, sent_at, created_at, updated_at)
            VALUES (?, ?, ?, 'EMAIL', 'SENT', 1, ?, ?, ?)
            """)
        .params(UUID.randomUUID(), walk, savedAt, savedAt, savedAt, savedAt)
        .update();

    AppliedPlan switched = service().apply(USER, body(swimAndSleep.toString()));
    assertThat(switched.changed()).isTrue();
    assertThat(switched.remindersTurnedOff()).isEqualTo(1);
    assertThat(enabled(walk)).isFalse();
    assertThat(reason(walk)).isEqualTo("PLAN_SWITCH");
    assertThat(reason(walkOff)).isNull(); // turned off by the user, not by the switch
    assertThat(enabled(sleep)).isTrue(); // the new plan also has "sleep"
    assertThat(enabled(standalone)).isTrue();
    assertThat(enabled(walkOff)).isFalse();
    assertThat(enabled(otherUsersWalk)).isTrue();
    assertThat(
            jdbc.sql("SELECT count(*) FROM lifestyle.reminder_deliveries WHERE reminder_id = ? AND status = 'SENT'")
                .param(walk)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    assertThat(service().apply(USER, body(swimAndSleep.toString())).remindersTurnedOff()).isZero();

    // At the reminder time only the reminders still on are sent.
    ReminderDispatcherTest.FakeChannel channel = new ReminderDispatcherTest.FakeChannel();
    ReminderDispatcher.Result result =
        new ReminderDispatcher(
                reminders,
                new JdbcConsentQuery(jdbc),
                List.of(channel),
                Clock.fixed(NOW, VIETNAM),
                ReminderDispatcherTest.PROPERTIES)
            .runOnce();
    assertThat(result).isEqualTo(new ReminderDispatcher.Result(3, 0));
    assertThat(channel.sent).containsExactlyInAnyOrder(USER, USER, OTHER_USER);
    assertThat(
            jdbc.sql("SELECT count(*) FROM lifestyle.reminder_deliveries WHERE reminder_id = ?")
                .param(walk)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  /**
   * SCRUM-162: switching back offers the paused reminders without turning them on; paused reminders
   * are outside the reminder limit until turned on again.
   */
  @Test
  void switchingBackOffersPausedRemindersAndTheyCountAgainOnlyWhenTurnedOn() throws Exception {
    UUID swim = UUID.fromString("00000000-0000-4000-8000-000000000718");
    jdbc.sql(
            """
            INSERT INTO lifestyle.templates
              (id, title, goal, activities, review_status, source_url, is_synthetic, summary, duration_days)
            VALUES (?, '[TEST] Bơi', 'SLEEP', '[{"id":"swim","title":"[TEST] Bơi"}]'::jsonb,
                    'APPROVED', 'https://example.invalid/template', false, '[TEST] Mô tả', 14)
            """)
        .param(swim)
        .update();
    service().apply(USER, body(SLEEP.toString())); // activities walk, sleep
    LifestyleReminderService reminders =
        new LifestyleReminderService(
            new ReminderRepository(jdbc),
            repository,
            json,
            Clock.fixed(NOW, VIETNAM),
            new JdbcConsentQuery(jdbc),
            List.of(new ReminderDispatcherTest.FakeChannel()),
            2);
    String walk = reminders.create(USER, json.readTree("{\"activityId\":\"walk\",\"localTime\":\"07:00\"}")).id();
    String sleep = reminders.create(USER, json.readTree("{\"activityId\":\"sleep\",\"localTime\":\"22:00\"}")).id();
    assertApiError(
        () -> reminders.create(USER, json.readTree("{\"content\":\"[TEST] Uống nước\",\"localTime\":\"08:00\"}")),
        HttpStatus.CONFLICT,
        "REMINDER_LIMIT_REACHED");

    AppliedPlan toSwim = service().apply(USER, body(swim.toString()));
    assertThat(toSwim.remindersTurnedOff()).isEqualTo(2);
    assertThat(toSwim.restorableReminderIds()).isEmpty();
    // Both are paused, so they no longer take the two places.
    String water = reminders.create(USER, json.readTree("{\"content\":\"[TEST] Uống nước\",\"localTime\":\"08:00\"}")).id();
    assertThat(reminders.list(USER).reminders())
        .filteredOn(r -> r.id().equals(walk))
        .singleElement()
        .satisfies(r -> {
          assertThat(r.enabled()).isFalse();
          assertThat(r.disabledReason()).isEqualTo("PLAN_SWITCH");
          assertThat(r.nextSendAt()).isNull();
        });
    // Turning a paused reminder off again keeps it paused (and outside the limit).
    reminders.update(USER, sleep, json.readTree("{\"enabled\":false}"));
    assertThat(reason(UUID.fromString(sleep))).isEqualTo("PLAN_SWITCH");

    AppliedPlan back = service().apply(USER, body(SLEEP.toString()));
    assertThat(back.remindersTurnedOff()).isZero(); // "water" has no activity
    assertThat(back.restorableReminderIds()).containsExactly(walk, sleep);
    assertThat(enabled(UUID.fromString(walk))).isFalse(); // offered, not turned on

    reminders.create(USER, json.readTree("{\"content\":\"[TEST] Đi ngủ\",\"localTime\":\"21:00\"}"));
    // Two reminders count now; turning a paused one on would make three.
    assertApiError(
        () -> reminders.update(USER, walk, json.readTree("{\"enabled\":true}")),
        HttpStatus.CONFLICT,
        "REMINDER_LIMIT_REACHED");
    reminders.delete(USER, water);
    assertThat(reminders.update(USER, walk, json.readTree("{\"enabled\":true}")).enabled()).isTrue();
    assertThat(reason(UUID.fromString(walk))).isNull();
  }

  /** SCRUM-163: ended plans with figures from the completions, then from the kept summary. */
  @Test
  void historyListsEndedPlansOfTheUserWithTheirFigures() {
    LifestyleService habits =
        new LifestyleService(repository, json, Clock.fixed(NOW, VIETNAM), new JdbcConsentQuery(jdbc), 100);
    assertThat(habits.pastPlans(USER).plans()).isEmpty();

    AppliedPlan first = service().apply(USER, body(SLEEP.toString())); // activities walk, sleep
    jdbc.sql("UPDATE lifestyle.plans SET started_on = ? WHERE id = ?::uuid")
        .params(TODAY.minusDays(13), first.planId())
        .update();
    for (Object[] done :
        List.of(
            new Object[] {"walk", TODAY.minusDays(13)},
            new Object[] {"sleep", TODAY.minusDays(13)},
            new Object[] {"walk", TODAY.minusDays(12)})) {
      jdbc.sql("INSERT INTO lifestyle.completions (plan_id, activity_id, completed_on) VALUES (?::uuid, ?, ?)")
          .params(first.planId(), done[0], done[1])
          .update();
    }
    AppliedPlan second = service().apply(USER, body(SLEEP_ALT.toString()));
    service().apply(USER, body(SLEEP.toString()));
    service().apply(OTHER_USER, body(SLEEP.toString()));
    service().apply(OTHER_USER, body(SLEEP_ALT.toString()));

    List<PlanHistory.PastPlan> past = habits.pastPlans(USER).plans();
    assertThat(past).extracting(PlanHistory.PastPlan::planId).containsExactly(second.planId(), first.planId());
    assertThat(past.get(1))
        .satisfies(p -> {
          assertThat(p.templateId()).isEqualTo(SLEEP.toString());
          assertThat(p.templateTitle()).isEqualTo("[TEST] Ngủ đúng giờ");
          assertThat(p.startedOn()).isEqualTo(TODAY.minusDays(13));
          assertThat(p.endedOn()).isEqualTo(TODAY);
          assertThat(p.days()).isEqualTo(14);
          assertThat(p.activityCount()).isEqualTo(2);
          assertThat(p.completionCount()).isEqualTo(3);
          assertThat(p.completedDays()).isEqualTo(2);
          assertThat(p.completionRate()).isEqualTo(0.11); // 3 / (14 × 2)
        });
    assertThat(past.get(0).days()).isEqualTo(1);
    assertThat(past.get(0).completionRate()).isEqualTo(0.0);

    // Once the cleanup job keeps a summary and removes the rows, the figures stay the same.
    jdbc.sql(
            """
            UPDATE lifestyle.plans
            SET completion_count = 3, completed_days = 2, completions_summarized_at = now()
            WHERE id = ?::uuid
            """)
        .param(first.planId())
        .update();
    jdbc.sql("DELETE FROM lifestyle.completions WHERE plan_id = ?::uuid").param(first.planId()).update();
    assertThat(habits.pastPlans(USER).plans().get(1).completionCount()).isEqualTo(3);
    assertThat(habits.pastPlans(USER).plans().get(1).completedDays()).isEqualTo(2);

    assertThat(new LifestyleService(repository, json, Clock.fixed(NOW, VIETNAM), new JdbcConsentQuery(jdbc), 1)
            .pastPlans(USER)
            .plans())
        .hasSize(1);
    jdbc.sql("UPDATE accounts.consents SET health_data_processing_accepted = false WHERE user_id = ?")
        .param(USER)
        .update();
    assertApiError(() -> habits.pastPlans(USER), HttpStatus.FORBIDDEN, "HEALTH_CONSENT_REQUIRED");
  }

  void assertApiError(ThrowingCallable call, HttpStatus status, String code) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(status);
              assertThat(e.code()).isEqualTo(code);
            });
  }

  String reason(UUID reminderId) {
    return jdbc.sql("SELECT disabled_reason FROM lifestyle.reminders WHERE id = ?")
        .param(reminderId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  /** Every day at NOW's local time (10:00 in Vietnam), by email. */
  Fields reminder(String activityId, boolean enabled) {
    return new Fields(
        "[TEST] Nhắc",
        activityId,
        LocalTime.ofInstant(NOW, VIETNAM),
        List.of(1, 2, 3, 4, 5, 6, 7),
        VIETNAM,
        ReminderChannel.Type.EMAIL,
        enabled);
  }

  boolean enabled(UUID reminderId) {
    return jdbc.sql("SELECT enabled FROM lifestyle.reminders WHERE id = ?")
        .param(reminderId)
        .query(Boolean.class)
        .single();
  }

  @Test
  void aUserNeverChangesAnotherUsersPlan() {
    AppliedPlan mine = service().apply(USER, body(SLEEP.toString()));
    service().apply(OTHER_USER, body(SLEEP_ALT.toString()));
    service().apply(OTHER_USER, body(SLEEP.toString()));

    assertThat(repository.findActivePlan(USER).orElseThrow().id().toString()).isEqualTo(mine.planId());
    assertThat(plans(USER)).isEqualTo(1);
    assertThat(activePlans(OTHER_USER)).isEqualTo(1);
  }

  @Test
  void theDatabaseRefusesASecondActivePlan() {
    service().apply(USER, body(SLEEP.toString()));
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO lifestyle.plans (id, user_id, template_id, started_on) VALUES (?, ?, ?, ?)")
                    .params(UUID.randomUUID(), USER, SLEEP_ALT, TODAY)
                    .update())
        .isInstanceOf(DuplicateKeyException.class);
  }
}
