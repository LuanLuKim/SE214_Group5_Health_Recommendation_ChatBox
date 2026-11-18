package vn.edu.uit.se214.health.knowledge.authoring;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import vn.edu.uit.se214.health.knowledge.authoring.TemplateDraftInput.Activity;
import vn.edu.uit.se214.health.shared.domain.ReviewStatus;

/**
 * UC-15 plan template as the authoring API returns it. A template stored before UC-15 may have
 * {@code durationDays = null} and empty source, applicability or advisory; it must be completed before it
 * can be edited partially or submitted.
 */
public record TemplateDraft(
    String id,
    String title,
    String goal,
    String summary,
    List<Activity> activities,
    Integer durationDays,
    List<String> notSuitableFor,
    List<String> excludedAllergens,
    boolean requiresProfile,
    String applicability,
    String advisory,
    String sourceName,
    String sourceUrl,
    String sourceUpdatedAt,
    String copyrightNotes,
    String verifiedAt,
    ReviewStatus reviewStatus,
    String createdBy,
    String updatedBy,
    String createdAt,
    String updatedAt,
    int revision,
    String submittedAt,
    String submittedBy)
    implements Draft<TemplateDraftInput> {

  @Override
  @JsonIgnore
  public TemplateDraftInput input() {
    return new TemplateDraftInput(title, goal, summary, activities, durationDays, notSuitableFor,
        excludedAllergens, requiresProfile, applicability, advisory, sourceName, sourceUrl, sourceUpdatedAt,
        copyrightNotes);
  }

  @Override
  @JsonIgnore
  public boolean editable() {
    return Draft.super.editable();
  }
}
