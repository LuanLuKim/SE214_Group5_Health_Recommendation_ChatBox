package vn.edu.uit.se214.health.knowledge.authoring;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * UC-15 body of POST /api/v1/knowledge/templates (PATCH sends a subset). The fields are those M6 reads from
 * {@code lifestyle.templates} for UC-12 (title, goal, summary, daily activities, duration, groups it is not
 * suitable for, allergens, whether a health profile is required) plus the editorial ones: applicability,
 * advisory (ask a health worker) and the source. {@code goal} must be one of the goals allowed by the
 * {@code templates_goal_check} constraint of M6; the database refuses any other value.
 */
public record TemplateDraftInput(
    String title,
    String goal,
    String summary,
    List<Activity> activities,
    Integer durationDays,
    List<String> notSuitableFor,
    List<String> excludedAllergens,
    Boolean requiresProfile,
    String applicability,
    String advisory,
    String sourceName,
    String sourceUrl,
    String sourceUpdatedAt,
    String copyrightNotes)
    implements DraftInput<TemplateDraftInput> {

  /** One daily activity of the template, the shape M6 reads (PlanActivity). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Activity(String id, String title, String description) {}

  @Override
  public TemplateDraftInput validated() {
    if (activities == null || activities.isEmpty() || activities.size() > 100) throw DraftFields.invalid();
    List<Activity> cleanActivities = new ArrayList<>(activities.size());
    Set<String> ids = new HashSet<>();
    for (Activity activity : activities) {
      if (activity == null) throw DraftFields.invalid();
      String id = DraftFields.text(activity.id(), 100);
      if (!ids.add(id)) throw DraftFields.invalid();
      String description = DraftFields.optionalText(activity.description(), 2000);
      cleanActivities.add(new Activity(id, DraftFields.text(activity.title(), 300),
          description.isEmpty() ? null : description));
    }
    if (durationDays == null || durationDays < 1 || durationDays > 365) throw DraftFields.invalid();
    return new TemplateDraftInput(
        DraftFields.text(title, 300),
        DraftFields.text(goal, 40),
        DraftFields.optionalText(summary, 3000),
        List.copyOf(cleanActivities),
        durationDays,
        DraftFields.texts(notSuitableFor, 50, 200),
        DraftFields.texts(excludedAllergens, 50, 200),
        Boolean.TRUE.equals(requiresProfile),
        DraftFields.text(applicability, 3000),
        DraftFields.text(advisory, 3000),
        DraftFields.text(sourceName, 200),
        DraftFields.url(sourceUrl),
        DraftFields.sourceDate(sourceUpdatedAt),
        DraftFields.optionalText(copyrightNotes, 3000));
  }
}
