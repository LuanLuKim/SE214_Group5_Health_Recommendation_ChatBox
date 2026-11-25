package lifestyle;

import java.time.LocalDate;
import java.util.List;
import lifestyle.ProgressCalculator.DailyProgress;

/**
 * UC-13 {@code GET /api/v1/lifestyle/plan}: today's activities of the active plan.
 *
 * @param date today in the configured zone
 * @param planStreak consecutive days, ending today (or yesterday if today is not finished), on
 *     which every activity was completed
 */
public record TodayPlan(
    String planId,
    String templateId,
    String templateTitle,
    LocalDate startedOn,
    LocalDate date,
    DailyProgress progress,
    int planStreak,
    List<ActivityToday> activities) {

  /** One activity today: whether it is done and its own streak. */
  public record ActivityToday(String id, String title, boolean completed, int streak) {}
}
