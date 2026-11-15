package vn.edu.uit.se214.health.lifestyle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.edu.uit.se214.health.lifestyle.ProgressCalculator.DailyProgress;
import vn.edu.uit.se214.health.lifestyle.TodayPlan.ActivityToday;
import vn.edu.uit.se214.health.shared.web.ApiException;
import vn.edu.uit.se214.health.support.TestDatabase;

/** UC-13 against a migrated database. Synthetic users, templates and activities only. */
class LifestyleServiceTest {
  static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
  /** 10:00 on 14/11/2026 in Vietnam. */
  static final Instant NOW = Instant.parse("2026-11-14T03:00:00Z");
  static final LocalDate TODAY = LocalDate.parse("2026-11-14");
  static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000601");
  static final UUID OTHER_USER = UUID.fromString("00000000-0000-4000-8000-000000000602");
  static final UUID TEMPLATE = UUID.fromString("00000000-0000-4000-8000-000000000610");
  static final UUID PLAN = UUID.fromString("00000000-0000-4000-8000-000000000620");
  static final String ACTIVITIES =
      "[{\"id\":\"walk\",\"title\":\"[TEST] Đi bộ\"},{\"id\":\"sleep\",\"title\":\"[TEST] Ngủ sớm\"}]";

  JdbcClient jdbc;
  LifestyleRepository repository;

  @BeforeEach
  void freshDatabase() {
    jdbc = JdbcClient.create(TestDatabase.fresh());
    repository = new LifestyleRepository(jdbc);
    user(USER, "uc13-a@example.invalid");
    user(OTHER_USER, "uc13-b@example.invalid");
    template(TEMPLATE, ACTIVITIES);
    jdbc.sql("INSERT INTO lifestyle.plans (id, user_id, template_id, started_on) VALUES (?, ?, ?, ?)")
        .params(PLAN, USER, TEMPLATE, LocalDate.parse("2026-11-10"))
        .update();
  }

  void user(UUID id, String email) {
    jdbc.sql("INSERT INTO accounts.users (id, email, display_name, is_synthetic) VALUES (?, ?, '[TEST]', true)")
        .params(id, email)
        .update();
  }

  void template(UUID id, String activities) {
    jdbc.sql(
            """
            INSERT INTO lifestyle.templates (id, title, goal, activities, review_status, source_url, is_synthetic)
            VALUES (?, '[TEST] Lộ trình', 'SLEEP', ?::jsonb, 'APPROVED', 'https://example.invalid/template', true)
            """)
        .params(id, activities)
        .update();
  }

  LifestyleService at(Instant instant) {
    return new LifestyleService(repository, new ObjectMapper(), Clock.fixed(instant, VIETNAM));
  }

  LifestyleService service() {
    return at(NOW);
  }

  int rows() {
    return jdbc.sql("SELECT count(*) FROM lifestyle.completions").query(Integer.class).single();
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
  void todayListsTheActivitiesOfTheActivePlan() {
    TodayPlan plan = service().today(USER);
    assertThat(plan.planId()).isEqualTo(PLAN.toString());
    assertThat(plan.date()).isEqualTo(TODAY);
    assertThat(plan.activities())
        .containsExactly(
            new ActivityToday("walk", "[TEST] Đi bộ", false, 0),
            new ActivityToday("sleep", "[TEST] Ngủ sớm", false, 0));
    assertThat(plan.progress()).isEqualTo(new DailyProgress(2, 0, 0.0));
    assertThat(plan.planStreak()).isZero();
  }

  @Test
  void withoutAPlanTheUserIsSentToChooseOne() {
    fails(() -> service().today(OTHER_USER), HttpStatus.NOT_FOUND, "LIFESTYLE_PLAN_NOT_FOUND");
    fails(() -> service().complete(OTHER_USER, "walk", "2026-11-14"), HttpStatus.NOT_FOUND, "LIFESTYLE_PLAN_NOT_FOUND");
    assertThat(rows()).isZero();
  }

  @Test
  void markingTheSameActivityTwiceKeepsOneRow() {
    CompletionResult first = service().complete(USER, "walk", "2026-11-14");
    CompletionResult again = service().complete(USER, "walk", "2026-11-14");
    assertThat(again).isEqualTo(first);
    assertThat(first.completed()).isTrue();
    assertThat(first.streak()).isEqualTo(1);
    assertThat(first.progress()).isEqualTo(new DailyProgress(2, 1, 50.0));
    assertThat(rows()).isEqualTo(1);
  }

  @Test
  void streaksCountConsecutiveDaysPerActivityAndForTheWholePlan() {
    LifestyleService service = service();
    for (String day : new String[] {"2026-11-12", "2026-11-13", "2026-11-14"}) service.complete(USER, "walk", day);
    service.complete(USER, "sleep", "2026-11-14");

    TodayPlan plan = service.today(USER);
    assertThat(plan.activities()).extracting(ActivityToday::streak).containsExactly(3, 1);
    assertThat(plan.planStreak()).isEqualTo(1);
    assertThat(plan.progress()).isEqualTo(new DailyProgress(2, 2, 100.0));

    service.complete(USER, "sleep", "2026-11-13");
    assertThat(service.today(USER).planStreak()).isEqualTo(2);
  }

  @Test
  void aGapBreaksTheStreakAndAnUnfinishedTodayCountsFromYesterday() {
    LifestyleService service = service();
    service.complete(USER, "walk", "2026-11-11");
    service.complete(USER, "walk", "2026-11-13");
    ActivityToday walk = service.today(USER).activities().getFirst();
    assertThat(walk.completed()).isFalse();
    assertThat(walk.streak()).isEqualTo(1);
  }

  @Test
  void datesMustBeIsoAndWithinThePlanUpToToday() {
    LifestyleService service = service();
    for (String bad : new String[] {"2026-11-15", "2026-11-09", "14-11-2026", "2026-02-30", "", null})
      fails(() -> service.complete(USER, "walk", bad), HttpStatus.BAD_REQUEST, "INVALID_COMPLETION_DATE");
    service.complete(USER, "walk", "2026-11-10");
    assertThat(rows()).isEqualTo(1);
  }

  @Test
  void onlyActivitiesOfThePlanCanBeMarked() {
    fails(() -> service().complete(USER, "swim", "2026-11-14"), HttpStatus.NOT_FOUND, "ACTIVITY_NOT_IN_PLAN");
    fails(() -> service().uncomplete(USER, "swim", "2026-11-14"), HttpStatus.NOT_FOUND, "ACTIVITY_NOT_IN_PLAN");
    assertThat(rows()).isZero();
  }

  @Test
  void onlyTodaysMarkCanBeRemoved() {
    LifestyleService service = service();
    service.complete(USER, "walk", "2026-11-13");
    service.complete(USER, "walk", "2026-11-14");

    CompletionResult removed = service.uncomplete(USER, "walk", "2026-11-14");
    assertThat(removed.completed()).isFalse();
    assertThat(removed.streak()).isEqualTo(1);
    // Removing again is harmless.
    assertThat(service.uncomplete(USER, "walk", "2026-11-14").completed()).isFalse();

    fails(() -> service.uncomplete(USER, "walk", "2026-11-13"), HttpStatus.CONFLICT, "COMPLETION_LOCKED");
    assertThat(rows()).isEqualTo(1);
  }

  @Test
  void theDayChangesAtLocalMidnightNotUtc() {
    LifestyleService beforeMidnight = at(Instant.parse("2026-11-14T16:59:59Z"));
    LifestyleService afterMidnight = at(Instant.parse("2026-11-14T17:00:00Z"));
    assertThat(beforeMidnight.today(USER).date()).isEqualTo(TODAY);
    fails(() -> beforeMidnight.complete(USER, "walk", "2026-11-15"), HttpStatus.BAD_REQUEST, "INVALID_COMPLETION_DATE");
    assertThat(afterMidnight.complete(USER, "walk", "2026-11-15").completed()).isTrue();
    assertThat(afterMidnight.today(USER).date()).isEqualTo(LocalDate.parse("2026-11-15"));
  }

  @Test
  void anotherUsersPlanIsNeverReadOrChanged() {
    UUID otherPlan = UUID.fromString("00000000-0000-4000-8000-000000000621");
    jdbc.sql("INSERT INTO lifestyle.plans (id, user_id, template_id, started_on) VALUES (?, ?, ?, ?)")
        .params(otherPlan, OTHER_USER, TEMPLATE, LocalDate.parse("2026-11-10"))
        .update();
    service().complete(OTHER_USER, "walk", "2026-11-14");

    assertThat(service().today(USER).activities()).noneMatch(ActivityToday::completed);
    assertThat(service().today(OTHER_USER).planId()).isEqualTo(otherPlan.toString());
    assertThat(
            jdbc.sql("SELECT count(*) FROM lifestyle.completions WHERE plan_id = ?")
                .param(PLAN)
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void unreadableOrDuplicateActivitiesDoNotBreakTheDay() {
    jdbc.sql("UPDATE lifestyle.templates SET activities = ?::jsonb WHERE id = ?")
        .params("{\"not\":\"a list\"}", TEMPLATE)
        .update();
    assertThat(service().today(USER).activities()).isEmpty();
    assertThat(service().today(USER).progress()).isEqualTo(new DailyProgress(0, 0, 0.0));

    jdbc.sql("UPDATE lifestyle.templates SET activities = ?::jsonb WHERE id = ?")
        .params("[{\"id\":\"walk\",\"title\":\"A\"},{\"id\":\"walk\",\"title\":\"B\"},{\"title\":\"no id\"}]", TEMPLATE)
        .update();
    assertThat(service().today(USER).activities()).containsExactly(new ActivityToday("walk", "A", false, 0));
  }
}
