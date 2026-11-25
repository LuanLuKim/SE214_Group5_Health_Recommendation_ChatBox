package lifestyle;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * When a reminder fires: at {@code localTime} in the reminder's own zone, on its days of week.
 * Pure functions of the reminder and an instant, so tests need no clock.
 */
final class ReminderSchedule {
  private ReminderSchedule() {}

  /**
   * The occurrence to send now: the latest one at or before {@code now}, at most {@code window}
   * old and not before the schedule was saved. Yesterday is checked too, so 23:58 is still sent
   * at 00:03 the next day.
   */
  static Optional<Instant> due(ReminderRow reminder, Instant now, Duration window) {
    LocalDate today = now.atZone(reminder.zone()).toLocalDate();
    for (LocalDate day : new LocalDate[] {today, today.minusDays(1)}) {
      if (!reminder.daysOfWeek().contains(day.getDayOfWeek().getValue())) continue;
      Instant at = at(reminder, day);
      if (at.isAfter(now)) continue;
      if (Duration.between(at, now).compareTo(window) > 0) return Optional.empty();
      return at.isBefore(reminder.updatedAt()) ? Optional.empty() : Optional.of(at);
    }
    return Optional.empty();
  }

  /** The next occurrence strictly after {@code now}, for display. */
  static Instant next(ReminderRow reminder, Instant now) {
    LocalDate day = now.atZone(reminder.zone()).toLocalDate();
    for (int i = 0; i <= 7; i++, day = day.plusDays(1)) {
      if (!reminder.daysOfWeek().contains(day.getDayOfWeek().getValue())) continue;
      Instant at = at(reminder, day);
      if (at.isAfter(now)) return at;
    }
    throw new IllegalStateException("Reminder " + reminder.id() + " has no day of week");
  }

  /** A local time skipped by a daylight-saving gap moves forward, as ZonedDateTime resolves it. */
  private static Instant at(ReminderRow reminder, LocalDate day) {
    return ZonedDateTime.of(day, reminder.localTime(), reminder.zone()).toInstant();
  }
}
