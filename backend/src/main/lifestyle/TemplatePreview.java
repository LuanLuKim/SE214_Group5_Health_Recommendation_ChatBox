package lifestyle;

import java.util.List;

/**
 * UC-12 {@code GET /api/v1/lifestyle/templates/{id}}: the schedule and notes shown before the user
 * confirms. Reading it changes nothing (A4: cancelling keeps the current plan).
 *
 * @param current the template is the user's active plan
 * @param advice the recommendation to ask a health professional before starting
 */
public record TemplatePreview(
    String id,
    String title,
    LifestyleGoal goal,
    String summary,
    Integer durationDays,
    List<PlanActivity> activities,
    List<String> notSuitableFor,
    String sourceUrl,
    boolean current,
    String advice) {}
