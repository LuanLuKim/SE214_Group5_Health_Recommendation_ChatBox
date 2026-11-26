package lifestyle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import support.TestDatabase;

/** SCRUM-164 cleanup job against a migrated database, with a fake clock. Synthetic data only. */
class LifestyleRetentionTest {
  static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
  static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
  static final LocalDate TODAY = LocalDate.parse("2026-11-16");
  static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000901");
  static final UUID TEMPLATE = UUID.fromString("00000000-0000-4000-8000-000000000910");
  /** Batches of 2, so every step needs more than one batch. */
  static final RetentionProperties PROPERTIES =
      new RetentionProperties(
          true, "0 30 3 * * *", Duration.ofDays(90), Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(90), 2);

  DataSource dataSource;
  JdbcClient jdbc;
  LifestyleRetention retention;

  @BeforeEach
  void freshDatabase() {
    dataSource = TestDatabase.fresh();
    jdbc = JdbcClient.create(dataSource);
    retention =
        new LifestyleRetention(
            new RetentionRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource))),
            PROPERTIES,
            Clock.fixed(NOW, VIETNAM));
    jdbc.sql("INSERT INTO accounts.users (id, email, display_name, is_synthetic) VALUES (?, 'uc14-retention@example.invalid', '[TEST]', true)")
        .param(USER)
        .update();
    jdbc.sql(
            """
            INSERT INTO lifestyle.templates (id, title, goal, activities, review_status, source_url, is_synthetic)
            VALUES (?, '[TEST] Lộ trình', 'SLEEP',
                    '[{"id":"walk","title":"[TEST] Đi bộ"},{"id":"sleep","title":"[TEST] Ngủ sớm"}]'::jsonb,
                    'APPROVED', 'https://example.invalid/template', true)
            """)
        .param(TEMPLATE)
        .update();
  }

  UUID reminder(boolean enabled, String disabledReason, Duration unchangedFor) {
    UUID id = UUID.randomUUID();
    OffsetDateTime saved = at(NOW.minus(unchangedFor));
    jdbc.sql(
            """
            INSERT INTO lifestyle.reminders
              (id, user_id, content, activity_id, local_time, days_of_week, timezone, channel, enabled,
               disabled_reason, created_at, updated_at)
            VALUES (?, ?, '[TEST] Nhắc', NULL, '07:00', '{1,2,3,4,5,6,7}', 'Asia/Ho_Chi_Minh', 'EMAIL', ?, ?, ?, ?)
            """)
        .params(id, USER, enabled, disabledReason, saved, saved)
        .update();
    return id;
  }

  void delivery(UUID reminderId, String status, Duration ago) {
    OffsetDateTime scheduled = at(NOW.minus(ago));
    jdbc.sql(
            """
            INSERT INTO lifestyle.reminder_deliveries
              (id, reminder_id, scheduled_for, channel, status, attempt_count, sent_at, created_at, updated_at)
            VALUES (?, ?, ?, 'EMAIL', ?, 1, ?, ?, ?)
            """)
        .params(UUID.randomUUID(), reminderId, scheduled, status, "SENT".equals(status) ? scheduled : null, scheduled, scheduled)
        .update();
  }

  UUID plan(LocalDate startedOn, LocalDate endedOn) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO lifestyle.plans (id, user_id, template_id, started_on, ended_on) VALUES (?, ?, ?, ?, ?)")
        .params(id, USER, TEMPLATE, startedOn, endedOn)
        .update();
    return id;
  }

  void completion(UUID planId, String activityId, LocalDate day) {
    jdbc.sql("INSERT INTO lifestyle.completions (plan_id, activity_id, completed_on) VALUES (?, ?, ?)")
        .params(planId, activityId, day)
        .update();
  }

  int count(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query(Integer.class).single();
  }

  static OffsetDateTime at(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  @Test
  void removesOnlyWhatIsPastItsPeriodAndKeepsPlanFigures() {
    UUID kept = reminder(true, null, Duration.ofDays(400));
    for (int day = 91; day <= 94; day++) delivery(kept, "SENT", Duration.ofDays(day)); // 4 old sent
    delivery(kept, "SKIPPED", Duration.ofDays(95)); // old skipped
    delivery(kept, "SENT", Duration.ofDays(89)); // sent, still kept
    delivery(kept, "FAILED", Duration.ofDays(40)); // old failed
    delivery(kept, "PENDING", Duration.ofDays(31)); // stuck pending
    delivery(kept, "FAILED", Duration.ofDays(29)); // failed, still kept

    UUID pausedLongAgo = reminder(false, "PLAN_SWITCH", Duration.ofDays(91));
    delivery(pausedLongAgo, "SENT", Duration.ofDays(10)); // goes with its reminder
    UUID pausedRecently = reminder(false, "PLAN_SWITCH", Duration.ofDays(89));
    UUID offByUser = reminder(false, null, Duration.ofDays(500));

    UUID endedLongAgo = plan(TODAY.minusDays(420), TODAY.minusDays(366));
    completion(endedLongAgo, "walk", TODAY.minusDays(420));
    completion(endedLongAgo, "sleep", TODAY.minusDays(420));
    completion(endedLongAgo, "walk", TODAY.minusDays(419));
    UUID endedRecently = plan(TODAY.minusDays(370), TODAY.minusDays(364));
    completion(endedRecently, "walk", TODAY.minusDays(370));
    UUID active = plan(TODAY.minusDays(363), null);
    completion(active, "walk", TODAY.minusDays(363));

    assertThat(retention.runOnce()).isEqualTo(new LifestyleRetention.Result(5, 2, 1, 1));

    assertThat(count("SELECT count(*) FROM lifestyle.reminder_deliveries WHERE reminder_id = ?", kept)).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM lifestyle.reminder_deliveries WHERE reminder_id = ? AND status = 'SENT'", kept))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM lifestyle.reminders WHERE id = ?", pausedLongAgo)).isZero();
    assertThat(count("SELECT count(*) FROM lifestyle.reminder_deliveries WHERE reminder_id = ?", pausedLongAgo)).isZero();
    assertThat(count("SELECT count(*) FROM lifestyle.reminders WHERE id = ?", pausedRecently)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM lifestyle.reminders WHERE id = ?", offByUser)).isEqualTo(1);

    // The old plan keeps its figures; its rows are gone. Newer plans are untouched.
    assertThat(count("SELECT count(*) FROM lifestyle.completions WHERE plan_id = ?", endedLongAgo)).isZero();
    assertThat(count("SELECT completion_count FROM lifestyle.plans WHERE id = ?", endedLongAgo)).isEqualTo(3);
    assertThat(count("SELECT completed_days FROM lifestyle.plans WHERE id = ?", endedLongAgo)).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM lifestyle.completions WHERE plan_id = ?", endedRecently)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM lifestyle.plans WHERE id = ? AND completions_summarized_at IS NULL", endedRecently))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM lifestyle.completions WHERE plan_id = ?", active)).isEqualTo(1);

    // GET /plans/history still shows the summarized plan's figures.
    LifestyleRepository plans =
        new LifestyleRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    assertThat(plans.endedPlans(USER, 10))
        .filteredOn(p -> p.planId().equals(endedLongAgo))
        .singleElement()
        .satisfies(p -> {
          assertThat(p.completionCount()).isEqualTo(3);
          assertThat(p.completedDays()).isEqualTo(2);
        });

    // A second run finds nothing left to do.
    assertThat(retention.runOnce()).isEqualTo(new LifestyleRetention.Result(0, 0, 0, 0));
  }
}
