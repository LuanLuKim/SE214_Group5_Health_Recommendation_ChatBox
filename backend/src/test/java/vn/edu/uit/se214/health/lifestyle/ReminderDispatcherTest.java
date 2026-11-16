package vn.edu.uit.se214.health.lifestyle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    return new ReminderDispatcher(repository, List.of(channel), Clock.fixed(now, ZoneOffset.UTC), PROPERTIES)
        .runOnce();
  }

  List<Map<String, Object>> deliveries() {
    return jdbc.sql(
            "SELECT reminder_id, status, attempt_count, last_error_code FROM lifestyle.reminder_deliveries ORDER BY created_at")
        .query()
        .listOfRows();
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
  void twoWorkersAtOnceSendOnce() {
    reminder(USER, "07:00");
    ReminderDispatcher a =
        new ReminderDispatcher(repository, List.of(channel), Clock.fixed(SEVEN, ZoneOffset.UTC), PROPERTIES);
    ReminderDispatcher b =
        new ReminderDispatcher(repository, List.of(channel), Clock.fixed(SEVEN.plusSeconds(1), ZoneOffset.UTC), PROPERTIES);
    assertThat(a.runOnce().sent() + b.runOnce().sent()).isEqualTo(1);
    assertThat(channel.sent).hasSize(1);
  }
}
