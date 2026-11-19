package vn.edu.uit.se214.health.lifestyle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** UC-14 send times: local time in the reminder's zone, days of week, window, saved-after rule. */
class ReminderScheduleTest {
  static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
  static final Duration WINDOW = Duration.ofMinutes(10);
  static final List<Integer> EVERY_DAY = List.of(1, 2, 3, 4, 5, 6, 7);
  // Monday 16/11/2026, 00:00 in Vietnam.
  static final Instant SAVED = Instant.parse("2026-11-15T17:00:00Z");

  static ReminderRow reminder(String time, List<Integer> days, ZoneId zone, Instant updatedAt) {
    return new ReminderRow(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "[TEST] Uống nước",
        null,
        LocalTime.parse(time),
        days,
        zone,
        ReminderChannel.Type.EMAIL,
        true,
        null,
        updatedAt,
        null);
  }

  @Test
  void vietnamLocalTimeIsConvertedToUtc() {
    // 23:30 in Vietnam (UTC+7) is 16:30 UTC the same day.
    ReminderRow r = reminder("23:30", EVERY_DAY, VIETNAM, SAVED);
    Instant at = Instant.parse("2026-11-16T16:30:00Z");
    assertThat(ReminderSchedule.due(r, at, WINDOW)).contains(at);
    assertThat(ReminderSchedule.due(r, at.minusSeconds(1), WINDOW)).isEmpty();
    assertThat(ReminderSchedule.next(r, at.minusSeconds(1))).isEqualTo(at);
  }

  @Test
  void dueOnlyInsideTheWindow() {
    ReminderRow r = reminder("07:00", EVERY_DAY, VIETNAM, SAVED);
    Instant at = Instant.parse("2026-11-16T00:00:00Z");
    assertThat(ReminderSchedule.due(r, at.plus(WINDOW), WINDOW)).contains(at);
    assertThat(ReminderSchedule.due(r, at.plus(WINDOW).plusSeconds(1), WINDOW)).isEmpty();
  }

  @Test
  void onlyOnChosenDaysOfWeek() {
    // 16/11/2026 is a Monday (1); 17/11 a Tuesday (2).
    ReminderRow tuesdays = reminder("07:00", List.of(2), VIETNAM, SAVED);
    assertThat(ReminderSchedule.due(tuesdays, Instant.parse("2026-11-16T00:01:00Z"), WINDOW)).isEmpty();
    assertThat(ReminderSchedule.due(tuesdays, Instant.parse("2026-11-17T00:01:00Z"), WINDOW))
        .contains(Instant.parse("2026-11-17T00:00:00Z"));
    assertThat(ReminderSchedule.next(tuesdays, Instant.parse("2026-11-17T00:01:00Z")))
        .isEqualTo(Instant.parse("2026-11-24T00:00:00Z"));
  }

  @Test
  void justBeforeMidnightIsStillSentAfterMidnight() {
    ReminderRow r = reminder("23:58", List.of(1), VIETNAM, SAVED);
    // Monday 23:58 in Vietnam, checked on Tuesday 00:03.
    assertThat(ReminderSchedule.due(r, Instant.parse("2026-11-16T17:03:00Z"), WINDOW))
        .contains(Instant.parse("2026-11-16T16:58:00Z"));
  }

  @Test
  void anOccurrenceBeforeTheScheduleWasSavedIsNotSent() {
    // Saved at 07:05 for 07:00: nothing today, tomorrow as usual.
    ReminderRow r = reminder("07:00", EVERY_DAY, VIETNAM, Instant.parse("2026-11-16T00:05:00Z"));
    assertThat(ReminderSchedule.due(r, Instant.parse("2026-11-16T00:06:00Z"), WINDOW)).isEmpty();
    assertThat(ReminderSchedule.due(r, Instant.parse("2026-11-17T00:00:00Z"), WINDOW))
        .contains(Instant.parse("2026-11-17T00:00:00Z"));
  }

  @Test
  void eachReminderUsesItsOwnZone() {
    ReminderRow tokyo = reminder("07:00", EVERY_DAY, ZoneId.of("Asia/Tokyo"), SAVED);
    assertThat(ReminderSchedule.due(tokyo, Instant.parse("2026-11-15T22:00:00Z"), WINDOW))
        .contains(Instant.parse("2026-11-15T22:00:00Z"));
  }
}
