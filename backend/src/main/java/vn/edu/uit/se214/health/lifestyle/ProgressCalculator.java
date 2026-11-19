package vn.edu.uit.se214.health.lifestyle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** UC-13: daily progress and current streak, on local calendar days (zone from app.lifestyle.zone). */
public final class ProgressCalculator {
  private ProgressCalculator() {}

  public record Completion(String planId, String activityId, LocalDate completedOn) {}

  public record DailyProgress(int totalActivities, int completedActivities, double completionRate) {}

  /** Converts an instant to the calendar day in {@code zone} (local midnight, not UTC). */
  public static LocalDate localDate(Instant instant, ZoneId zone) {
    return instant.atZone(zone).toLocalDate();
  }

  /** Duplicates are ignored; completions of other plans, days or unscheduled activities do not count. */
  public static DailyProgress dailyProgress(
      String planId, LocalDate day, Collection<String> scheduledActivityIds, Collection<Completion> completions) {
    Set<String> scheduled = new LinkedHashSet<>(scheduledActivityIds);
    Set<String> completed = new HashSet<>();
    for (Completion row : completions) {
      if (row.planId().equals(planId)
          && row.completedOn().equals(day)
          && scheduled.contains(row.activityId())) completed.add(row.activityId());
    }
    double rate =
        scheduled.isEmpty() ? 0 : Math.round(completed.size() * 10_000.0 / scheduled.size()) / 100.0;
    return new DailyProgress(scheduled.size(), completed.size(), rate);
  }

  /**
   * Consecutive completed days ending today, or yesterday if today is not done yet.
   * The dates must belong to a single plan and activity. Future days are ignored.
   */
  public static int currentStreak(Collection<LocalDate> completionDates, LocalDate today) {
    Set<LocalDate> completed = new HashSet<>();
    for (LocalDate day : completionDates) if (!day.isAfter(today)) completed.add(day);
    LocalDate cursor = completed.contains(today) ? today : today.minusDays(1);
    int streak = 0;
    while (completed.contains(cursor)) {
      streak++;
      cursor = cursor.minusDays(1);
    }
    return streak;
  }
}
