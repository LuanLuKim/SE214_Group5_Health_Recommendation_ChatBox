package vn.edu.uit.se214.health.lifestyle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.edu.uit.se214.health.accounts.api.ConsentQuery;
import vn.edu.uit.se214.health.lifestyle.ReminderRepository.Fields;
import vn.edu.uit.se214.health.support.TestDatabase;

/** UC-14 worker against a migrated database, with a fake clock and a fake channel. */
class ReminderDispatcherTest {
  static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
  static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000801");
  static final UUID OTHER_USER = UUID.fromString("00000000-0000-4000-8000-000000000802");
  // Monday 16/11/2026: saved at 06:00, sent at 07:00 in Vietnam (00:00 UTC).
  static final Instant SAVED = Instant.parse("2026-11-15T23:00:00Z");
  static final Instant SEVEN = Instant.parse("2026-11-16T00:00:00Z");
  static final ReminderProperties PROPERTIES =
      new ReminderProperties(true, Duration.ofMinutes(1), Duration.ofMinutes(10), 3, 20, "reminders@example.invalid");

  JdbcClient jdbc;
  ReminderRepository repository;
  FakeChannel channel = new FakeChannel();
  /** Users who withdrew consent to health-data processing; everyone else consents. */
  final Set<UUID> withdrawn = new HashSet<>();
  final ConsentQuery consents = userId -> !withdrawn.contains(userId);

  /** Records sends; fails for the users in {@code failing}. */
  static class FakeChannel implements ReminderChannel {
    final List<UUID> sent = new ArrayList<>();
    final Set<UUID> failing = new HashSet<>();

    @Override
    public Type type() {
      return Type.EMAIL;
    }

    @Override
    public boolean available(UUID userId) {
      return true;
    }

    @Override
    public void send(UUID userId, String content) throws SendFailure {
      if (failing.contains(userId)) throw new SendFailure("TEST_FAILURE");
      sent.add(userId);
    }
  }

  @BeforeEach
  void freshDatabase() {
    DataSource dataSource = TestDatabase.fresh();
    jdbc = JdbcClient.create(dataSource);
    repository = new ReminderRepository(jdbc);
    user(USER, "uc14-a@example.invalid");
    user(OTHER_USER, "uc14-b@example.invalid");
  }

  void user(UUID id, String email) {
    jdbc.sql("INSERT INTO accounts.users (id, email, display_name, is_synthetic) VALUES (?, ?, '[TEST]', true)")
        .params(id, email)
        .update();
  }

  UUID reminder(UUID userId, String time, List<Integer> days, ReminderChannel.Type type, boolean enabled) {
    UUID id = UUID.randomUUID();
    repository.insert(
        id, userId, new Fields("[TEST] Uống nước", null, LocalTime.parse(time), days, VIETNAM, type, enabled), SAVED);
    return id;
  }

  UUID reminder(UUID userId, String time) {
    return reminder(userId, time, List.of(1, 2, 3, 4, 5, 6, 7), ReminderChannel.Type.EMAIL, true);
  }

  ReminderDispatcher.Result run(Instant now) {
    return new ReminderDispatcher(repository, consents, List.of(channel), Clock.fixed(now, ZoneOffset.UTC), PROPERTIES)
        .runOnce();
  }

  List<Map<String, Object>> deliveries() {
    return jdbc.sql(
            "SELECT reminder_id, status, attempt_count, last_error_code FROM lifestyle.reminder_deliveries ORDER BY created_at")
        .query()
        .listOfRows();
  }

  @Test
  void aRetryWriteFailureDoesNotStopOtherReminders() {
    reminder(USER, "07:00");
    channel.failing.add(USER);
    assertThat(run(SEVEN).failed()).isEqualTo(1);
    reminder(OTHER_USER, "07:01");
    channel.failing.clear();
    jdbc.sql("""
        CREATE FUNCTION fail_retry_mark_sent() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          IF OLD.attempt_count > 0 AND NEW.status = 'SENT' THEN
            RAISE EXCEPTION 'synthetic retry status write failure';
          END IF;
          RETURN NEW;
        END;
        $$""").update();
    jdbc.sql("""
        CREATE TRIGGER fail_retry_mark_sent BEFORE UPDATE ON lifestyle.reminder_deliveries
        FOR EACH ROW EXECUTE FUNCTION fail_retry_mark_sent()
        """).update();
    org.assertj.core.api.Assertions.assertThatCode(() -> run(SEVEN.plusSeconds(60)))
        .as("retry write errors must not abort the entire worker cycle")
        .doesNotThrowAnyException();
    assertThat(channel.sent).contains(OTHER_USER);
  }

  @Test
  void sendsOnceAtTheTimeAndNeverTwice() {
    UUID id = reminder(USER, "07:00");
    assertThat(run(SEVEN.minusSeconds(60)).sent()).isZero();

    assertThat(run(SEVEN.plusSeconds(20)).sent()).isEqualTo(1);
    assertThat(run(SEVEN.plusSeconds(80)).sent()).isZero();
    assertThat(channel.sent).containsExactly(USER);
    assertThat(deliveries()).singleElement().satisfies(d -> {
      assertThat(d).containsEntry("reminder_id", id).containsEntry("status", "SENT");
      assertThat(((Number) d.get("attempt_count")).intValue()).isEqualTo(1);
    });

    // Tuesday at 07:00 is a new send.
    assertThat(run(SEVEN.plus(Duration.ofDays(1))).sent()).isEqualTo(1);
    assertThat(deliveries()).hasSize(2);
  }

  @Test
  void offRemindersAndOtherDaysAreNotSent() {
    reminder(USER, "07:00", List.of(1, 2, 3, 4, 5, 6, 7), ReminderChannel.Type.EMAIL, false);
    reminder(USER, "07:00", List.of(2), ReminderChannel.Type.EMAIL, true); // Tuesdays only
    assertThat(run(SEVEN.plusSeconds(30)).sent()).isZero();
    assertThat(channel.sent).isEmpty();
    assertThat(deliveries()).isEmpty();
  }

  @Test
  void turningOffStopsLaterSends() {
    UUID id = reminder(USER, "07:00");
    run(SEVEN);
    jdbc.sql("UPDATE lifestyle.reminders SET enabled = false WHERE id = ?").param(id).update();
    assertThat(run(SEVEN.plus(Duration.ofDays(1))).sent()).isZero();
    assertThat(channel.sent).hasSize(1);
  }

  @Test
  void lateBeyondTheWindowIsSkipped() {
    reminder(USER, "07:00");
    assertThat(run(SEVEN.plus(Duration.ofMinutes(11))).sent()).isZero();
    assertThat(deliveries()).isEmpty();
  }

  @Test
  void vietnamEveningIsAfternoonUtc() {
    reminder(USER, "23:30");
    assertThat(run(Instant.parse("2026-11-16T16:29:00Z")).sent()).isZero();
    assertThat(run(Instant.parse("2026-11-16T16:30:00Z")).sent()).isEqualTo(1);
  }

  @Test
  void aFailedSendIsRetriedOnTheSameRowUpToTheLimit() {
    reminder(USER, "07:00");
    channel.failing.add(USER);
    assertThat(run(SEVEN).failed()).isEqualTo(1);
    assertThat(run(SEVEN.plusSeconds(60)).failed()).isEqualTo(1);
    assertThat(run(SEVEN.plusSeconds(120)).failed()).isEqualTo(1);
    // Three attempts used: no fourth, and no second row for the same time.
    assertThat(run(SEVEN.plusSeconds(180))).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(deliveries()).singleElement().satisfies(d -> {
      assertThat(d).containsEntry("status", "FAILED").containsEntry("last_error_code", "TEST_FAILURE");
      assertThat(((Number) d.get("attempt_count")).intValue()).isEqualTo(3);
    });
  }

  @Test
  void aRetryThatSucceedsIsSentOnce() {
    reminder(USER, "07:00");
    channel.failing.add(USER);
    run(SEVEN);
    channel.failing.clear();
    assertThat(run(SEVEN.plusSeconds(60)).sent()).isEqualTo(1);
    assertThat(run(SEVEN.plusSeconds(120)).sent()).isZero();
    assertThat(channel.sent).containsExactly(USER);
    assertThat(deliveries()).singleElement().satisfies(d -> {
      assertThat(d).containsEntry("status", "SENT");
      assertThat(d.get("last_error_code")).isNull();
      assertThat(((Number) d.get("attempt_count")).intValue()).isEqualTo(2);
    });
  }

  Fields everyDay(String time, boolean enabled) {
    return new Fields(
        "[TEST] Uống nước", null, LocalTime.parse(time), List.of(1, 2, 3, 4, 5, 6, 7), VIETNAM,
        ReminderChannel.Type.EMAIL, enabled);
  }

  void assertOneFailedAttempt() {
    assertThat(deliveries()).singleElement().satisfies(d -> {
      assertThat(d).containsEntry("status", "FAILED");
      assertThat(((Number) d.get("attempt_count")).intValue()).isEqualTo(1);
    });
  }

  /** Test 14-01: the 07:00 send failed, then the time was moved to 08:00 before the retry. */
  @Test
  void aFailedSendIsNotRetriedAfterTheTimeIsChanged() {
    UUID id = reminder(USER, "07:00");
    channel.failing.add(USER);
    assertThat(run(SEVEN).failed()).isEqualTo(1);
    channel.failing.clear();
    repository.update(USER, id, everyDay("08:00", true), SEVEN.plusSeconds(30));

    assertThat(run(SEVEN.plusSeconds(60))).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(channel.sent).isEmpty();
    assertOneFailedAttempt();
    // The new time is sent as usual.
    assertThat(run(SEVEN.plus(Duration.ofHours(1))).sent()).isEqualTo(1);
  }

  /** Test 14-13: the 07:00 send failed, then the reminder was turned off and on before the retry. */
  @Test
  void aFailedSendIsNotRetriedAfterTurningOffAndOnAgain() {
    UUID id = reminder(USER, "07:00");
    channel.failing.add(USER);
    run(SEVEN);
    channel.failing.clear();
    repository.update(USER, id, everyDay("07:00", false), SEVEN.plusSeconds(15));
    repository.update(USER, id, everyDay("07:00", true), SEVEN.plusSeconds(45));

    assertThat(run(SEVEN.plusSeconds(60))).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(channel.sent).isEmpty();
    assertOneFailedAttempt();
  }

  @Test
  void aSaveBetweenReadingAndClaimingStopsTheRetry() {
    UUID id = reminder(USER, "07:00");
    channel.failing.add(USER);
    run(SEVEN);
    channel.failing.clear();
    repository =
        new ReminderRepository(jdbc) {
          @Override
          List<ReminderRepository.Retry> retryable(int maxAttempts, Instant since) {
            List<ReminderRepository.Retry> found = super.retryable(maxAttempts, since);
            assertThat(found).hasSize(1);
            update(USER, id, everyDay("08:00", true), SEVEN.plusSeconds(50));
            return found;
          }
        };

    assertThat(run(SEVEN.plusSeconds(60))).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(channel.sent).isEmpty();
    assertOneFailedAttempt();
  }

  /** Only the flag changes, so these tests check {@code enabled} alone, not the saved time. */
  void turnOffKeepingTheSavedTime(UUID id) {
    jdbc.sql("UPDATE lifestyle.reminders SET enabled = false WHERE id = ?").param(id).update();
  }

  /** Test 14-08: turned off after the worker read the retries and before it claimed one. */
  @Test
  void turningOffBetweenReadingAndClaimingStopsTheRetry() {
    UUID id = reminder(USER, "07:00");
    channel.failing.add(USER);
    run(SEVEN);
    channel.failing.clear();
    repository =
        new ReminderRepository(jdbc) {
          @Override
          List<ReminderRepository.Retry> retryable(int maxAttempts, Instant since) {
            List<ReminderRepository.Retry> found = super.retryable(maxAttempts, since);
            assertThat(found).hasSize(1);
            turnOffKeepingTheSavedTime(id);
            return found;
          }
        };

    assertThat(run(SEVEN.plusSeconds(60))).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(channel.sent).isEmpty();
    assertOneFailedAttempt();
  }

  @Test
  void turningOffBetweenReadingAndClaimingStopsANewSend() {
    UUID id = reminder(USER, "07:00");
    repository =
        new ReminderRepository(jdbc) {
          @Override
          List<ReminderRow> enabled() {
            List<ReminderRow> found = super.enabled();
            assertThat(found).hasSize(1);
            turnOffKeepingTheSavedTime(id);
            return found;
          }
        };

    assertThat(run(SEVEN)).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(channel.sent).isEmpty();
    assertThat(deliveries()).isEmpty();
  }

  @Test
  void aSaveBetweenReadingAndClaimingStopsANewSend() {
    UUID id = reminder(USER, "07:00");
    repository =
        new ReminderRepository(jdbc) {
          @Override
          List<ReminderRow> enabled() {
            List<ReminderRow> found = super.enabled();
            assertThat(found).hasSize(1);
            update(USER, id, everyDay("08:00", true), SEVEN.plusSeconds(10));
            return found;
          }
        };

    assertThat(run(SEVEN.plusSeconds(20))).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(deliveries()).isEmpty();
  }

  @Test
  void twoWorkersClaimTheSameRetryOnce() throws Exception {
    UUID id = reminder(USER, "07:00");
    channel.failing.add(USER);
    assertThat(run(SEVEN)).isEqualTo(new ReminderDispatcher.Result(0, 1));
    UUID deliveryId = jdbc.sql("SELECT id FROM lifestyle.reminder_deliveries WHERE reminder_id = ?")
        .param(id).query(UUID.class).single();

    var atClaim = new CyclicBarrier(2);
    var sharedRepository = new ReminderRepository(jdbc) {
      @Override
      boolean claimRetry(UUID candidate, int maxAttempts, Instant now) {
        assertThat(candidate).isEqualTo(deliveryId);
        // Both workers have read the same FAILED row before either runs the real SQL UPDATE.
        try {
          atClaim.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError("Interrupted waiting for both retry claims", e);
        } catch (BrokenBarrierException | TimeoutException e) {
          throw new AssertionError("Both workers must reach the retry claim", e);
        }
        return super.claimRetry(candidate, maxAttempts, now);
      }
    };
    var firstChannel = new FakeChannel();
    var secondChannel = new FakeChannel();
    var retryTime = Clock.fixed(SEVEN.plusSeconds(60), ZoneOffset.UTC);
    var first = new ReminderDispatcher(sharedRepository, consents, List.of(firstChannel), retryTime, PROPERTIES);
    var second = new ReminderDispatcher(sharedRepository, consents, List.of(secondChannel), retryTime, PROPERTIES);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var firstRun = workers.submit(first::runOnce);
      var secondRun = workers.submit(second::runOnce);
      assertThat(List.of(firstRun.get(15, TimeUnit.SECONDS), secondRun.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(new ReminderDispatcher.Result(1, 0), new ReminderDispatcher.Result(0, 0));
    }
    var recipients = new ArrayList<>(firstChannel.sent);
    recipients.addAll(secondChannel.sent);
    assertThat(recipients).containsExactly(USER);
    assertThat(jdbc.sql("""
        SELECT id, status, attempt_count, last_error_code, sent_at
        FROM lifestyle.reminder_deliveries WHERE reminder_id = ?
        """).param(id).query().listOfRows()).singleElement().satisfies(row -> {
          assertThat(row).containsEntry("id", deliveryId).containsEntry("status", "SENT")
              .containsEntry("attempt_count", 2).containsEntry("last_error_code", null);
          assertThat(row.get("sent_at")).isNotNull();
        });
  }

  @Test
  void aFailedSendIsNotRetriedAfterTheWindow() {
    UUID id = reminder(USER, "07:00");
    channel.failing.add(USER);
    assertThat(run(SEVEN)).isEqualTo(new ReminderDispatcher.Result(0, 1));
    channel.failing.clear();

    assertThat(run(SEVEN.plus(PROPERTIES.window()).plusSeconds(1)))
        .isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(channel.sent).isEmpty();
    assertThat(deliveries()).singleElement().satisfies(row -> assertThat(row)
        .containsEntry("reminder_id", id).containsEntry("status", "FAILED")
        .containsEntry("attempt_count", 1).containsEntry("last_error_code", "TEST_FAILURE"));
  }

  @Test
  void aRepeatedLocalTimeAtDstFallbackIsSentOnce() {
    ZoneId newYork = ZoneId.of("America/New_York");
    Instant firstLocalTime = Instant.parse("2026-11-01T05:30:00Z");
    Instant secondLocalTime = Instant.parse("2026-11-01T06:30:00Z");
    UUID id = UUID.randomUUID();
    repository.insert(id, USER,
        new Fields("[TEST] DST overlap", null, LocalTime.of(1, 30), List.of(7), newYork,
            ReminderChannel.Type.EMAIL, true),
        firstLocalTime.minusSeconds(3600));

    assertThat(run(firstLocalTime)).isEqualTo(new ReminderDispatcher.Result(1, 0));
    assertThat(run(secondLocalTime)).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(channel.sent).containsExactly(USER);
    assertThat(jdbc.sql("""
        SELECT scheduled_for FROM lifestyle.reminder_deliveries WHERE reminder_id = ?
        """).param(id).query(OffsetDateTime.class).single().toInstant()).isEqualTo(firstLocalTime);
  }

  @Test
  void yesterdaysFailureDoesNotBlockTodaysSend() {
    UUID id = reminder(USER, "07:00");
    channel.failing.add(USER);
    assertThat(run(SEVEN)).isEqualTo(new ReminderDispatcher.Result(0, 1));
    channel.failing.clear();
    Instant tomorrow = SEVEN.plus(Duration.ofDays(1));

    assertThat(run(tomorrow)).isEqualTo(new ReminderDispatcher.Result(1, 0));
    assertThat(channel.sent).containsExactly(USER);
    assertThat(jdbc.sql("""
        SELECT scheduled_for, status, attempt_count FROM lifestyle.reminder_deliveries
        WHERE reminder_id = ? ORDER BY scheduled_for
        """).param(id).query((row, index) -> List.of(row.getObject("scheduled_for", OffsetDateTime.class).toInstant(),
            row.getString("status"), row.getInt("attempt_count"))).list())
        .containsExactly(List.of(SEVEN, "FAILED", 1), List.of(tomorrow, "SENT", 1));
  }

  @Test
  void aFailedSentWriteIsNotSentTwice() {
    UUID affected = reminder(USER, "07:00");
    UUID unaffected = reminder(OTHER_USER, "07:00");
    jdbc.sql("""
        CREATE FUNCTION fail_first_mark_sent() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          RAISE EXCEPTION 'synthetic first-attempt status write failure';
          RETURN NEW;
        END;
        $$""").update();
    jdbc.sql("""
        CREATE TRIGGER fail_first_mark_sent BEFORE UPDATE ON lifestyle.reminder_deliveries
        FOR EACH ROW WHEN (NEW.status = 'SENT' AND OLD.reminder_id = '%s'::uuid)
        EXECUTE FUNCTION fail_first_mark_sent()
        """.formatted(affected)).update();
    assertThat(run(SEVEN)).isEqualTo(new ReminderDispatcher.Result(1, 1));
    assertThat(run(SEVEN.plusSeconds(60))).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(channel.sent).containsExactlyInAnyOrder(USER, OTHER_USER);
    assertThat(deliveries()).hasSize(2).anySatisfy(row -> assertThat(row)
        .containsEntry("reminder_id", affected).containsEntry("status", "PENDING").containsEntry("attempt_count", 0))
        .anySatisfy(row -> assertThat(row).containsEntry("reminder_id", unaffected)
            .containsEntry("status", "SENT").containsEntry("attempt_count", 1));
  }

  @Test
  void aSkippedLocalTimeAtDstSpringForwardIsSentOnceLater() {
    ZoneId newYork = ZoneId.of("America/New_York");
    Instant shiftedTime = Instant.parse("2026-03-08T07:30:00Z");
    UUID id = UUID.randomUUID();
    repository.insert(id, USER,
        new Fields("[TEST] DST gap", null, LocalTime.of(2, 30), List.of(7), newYork,
            ReminderChannel.Type.EMAIL, true), shiftedTime.minus(Duration.ofHours(2)));

    assertThat(run(shiftedTime.minusSeconds(1))).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(run(shiftedTime)).isEqualTo(new ReminderDispatcher.Result(1, 0));
    assertThat(run(shiftedTime.plusSeconds(60))).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(channel.sent).containsExactly(USER);
    assertThat(jdbc.sql("SELECT scheduled_for FROM lifestyle.reminder_deliveries WHERE reminder_id = ?")
        .param(id).query(OffsetDateTime.class).single().toInstant()).isEqualTo(shiftedTime);
  }

  @Test
  void oneFailingChannelDoesNotStopTheOthers() {
    reminder(USER, "07:00");
    reminder(OTHER_USER, "07:00");
    channel.failing.add(USER);
    assertThat(run(SEVEN)).isEqualTo(new ReminderDispatcher.Result(1, 1));
    assertThat(channel.sent).containsExactly(OTHER_USER);
  }

  @Test
  void aChannelWithoutImplementationFailsWithACode() {
    reminder(USER, "07:00", List.of(1, 2, 3, 4, 5, 6, 7), ReminderChannel.Type.WEB_PUSH, true);
    assertThat(run(SEVEN).failed()).isEqualTo(1);
    assertThat(deliveries()).singleElement().satisfies(d -> assertThat(d).containsEntry("last_error_code", "CHANNEL_UNAVAILABLE"));
  }

  @Test
  void noSendAndNoDeliveryRowWithoutConsent() {
    reminder(USER, "07:00");
    reminder(OTHER_USER, "07:00");
    withdrawn.add(USER);
    assertThat(run(SEVEN)).isEqualTo(new ReminderDispatcher.Result(1, 0));
    assertThat(channel.sent).containsExactly(OTHER_USER);
    assertThat(deliveries()).hasSize(1);

    // Consent given again: the next due time is sent as usual.
    withdrawn.clear();
    assertThat(run(SEVEN.plus(Duration.ofDays(1))).sent()).isEqualTo(2);
  }

  @Test
  void aFailedSendIsNotRetriedAfterConsentIsWithdrawn() {
    reminder(USER, "07:00");
    channel.failing.add(USER);
    assertThat(run(SEVEN).failed()).isEqualTo(1);
    channel.failing.clear();
    withdrawn.add(USER);
    assertThat(run(SEVEN.plusSeconds(60))).isEqualTo(new ReminderDispatcher.Result(0, 0));
    assertThat(channel.sent).isEmpty();
    assertThat(deliveries()).singleElement().satisfies(d -> {
      assertThat(d).containsEntry("status", "FAILED");
      assertThat(((Number) d.get("attempt_count")).intValue()).isEqualTo(1);
    });
  }

  @Test
  void twoWorkersAtOnceSendOnce() {
    reminder(USER, "07:00");
    ReminderDispatcher a =
        new ReminderDispatcher(repository, consents, List.of(channel), Clock.fixed(SEVEN, ZoneOffset.UTC), PROPERTIES);
    ReminderDispatcher b =
        new ReminderDispatcher(repository, consents, List.of(channel), Clock.fixed(SEVEN.plusSeconds(1), ZoneOffset.UTC), PROPERTIES);
    assertThat(a.runOnce().sent() + b.runOnce().sent()).isEqualTo(1);
    assertThat(channel.sent).hasSize(1);
  }
}
