package lifestyle;

import java.util.List;

/**
 * UC-12 {@code GET /api/v1/lifestyle/templates}: approved templates the user may apply.
 *
 * @param goal the requested goal, or null for every goal
 * @param profileMissing the user has no health profile (A1): allergies could not be checked, and
 *     templates that need a profile are left out; the client suggests updating the profile
 * @param message set when the list is empty (A3); no plan is ever generated in its place
 */
public record TemplateCatalog(
    LifestyleGoal goal, boolean profileMissing, List<TemplateSummary> templates, String message) {

  /** One template in the list (FR-2.6 step 3). */
  public record TemplateSummary(
      String id,
      String title,
      LifestyleGoal goal,
      String summary,
      Integer durationDays,
      int activityCount,
      List<String> notSuitableFor,
      String sourceUrl) {}
}
