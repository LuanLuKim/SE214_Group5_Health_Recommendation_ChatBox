package lifestyle;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /lifestyle/plans/history: the user's ended plans, latest first (SCRUM-163). Read only; the
 * figures come from lifestyle.completions, or from the summary kept on the plan once the cleanup
 * job has removed the detailed rows.
 */
public record PlanHistory(List<PastPlan> plans) {
  /**
   * One ended plan. {@code days} counts the calendar days from start to end, both included.
   * {@code completionRate} is completionCount / (days × activityCount), rounded to two decimals,
   * or null when the template has no activity.
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record PastPlan(
      String planId,
      String templateId,
      String templateTitle,
      LocalDate startedOn,
      LocalDate endedOn,
      int days,
      int activityCount,
      int completedDays,
      int completionCount,
      Double completionRate) {}
}
