package vn.edu.uit.se214.health.lifestyle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import vn.edu.uit.se214.health.accounts.api.ConsentQuery;
import vn.edu.uit.se214.health.health.api.HealthProfileQuery;
import vn.edu.uit.se214.health.lifestyle.LifestyleRepository.PlanWrite;
import vn.edu.uit.se214.health.lifestyle.TemplateCatalog.TemplateSummary;

/**
 * UC-12 Chọn lộ trình sinh hoạt: list approved templates for a goal, preview one, apply or switch.
 * Only approved templates are ever offered; nothing is generated (FR-2.6 step 6).
 *
 * <p>Every check runs again on apply, server side: consent, approval, declared allergies (A2) and
 * the profile requirement (A1). A template the user may not apply answers as if it did not exist,
 * except an allergen or a missing profile, which the user must be told about.
 */
public class LifestylePlanService {
  static final String ADVICE =
      "Lộ trình chỉ mang tính tham khảo, không thay thế tư vấn y tế. Hãy hỏi ý kiến nhân viên y tế"
          + " trước khi bắt đầu, nhất là khi bạn đang mang thai, có bệnh mạn tính hoặc đang dùng thuốc.";
  static final String EMPTY =
      "Chưa có mẫu lộ trình đã duyệt phù hợp với mục tiêu và hồ sơ của bạn. Hệ thống không tự tạo kế hoạch.";

  private final LifestyleRepository repository;
  private final ObjectMapper json;
  private final Clock clock;
  private final HealthProfileQuery profiles;
  private final ConsentQuery consents;
  private final boolean includeSynthetic;

  /**
   * @param includeSynthetic offer synthetic [TEST] templates: only when synthetic seeding is on,
   *     which the seed runner refuses in production
   */
  public LifestylePlanService(
      LifestyleRepository repository,
      ObjectMapper json,
      Clock clock,
      HealthProfileQuery profiles,
      ConsentQuery consents,
      boolean includeSynthetic) {
    this.repository = repository;
    this.json = json;
    this.clock = clock;
    this.profiles = profiles;
    this.consents = consents;
    this.includeSynthetic = includeSynthetic;
  }

  public TemplateCatalog templates(UUID userId, String goalParameter) {
    Optional<LifestyleGoal> goal = LifestyleGoal.parse(goalParameter);
    requireConsent(userId);
    Optional<Set<String>> allergies = profiles.foodAllergies(userId);
    List<TemplateSummary> offered =
        repository.approvedTemplates(goal, includeSynthetic).stream()
            .filter(t -> offerable(t, allergies))
            .map(this::summary)
            .toList();
    return new TemplateCatalog(
        goal.orElse(null), allergies.isEmpty(), offered, offered.isEmpty() ? EMPTY : null);
  }

  public TemplatePreview preview(UUID userId, String templateId) {
    UUID id = templateId(templateId);
    requireConsent(userId);
    TemplateRow template = eligible(userId, id);
    boolean current =
        repository.findActivePlan(userId).map(p -> p.templateId().equals(id)).orElse(false);
    return new TemplatePreview(
        id.toString(),
        template.title(),
        template.goal(),
        template.summary(),
        template.durationDays(),
        activities(template),
        template.notSuitableFor(),
        template.sourceUrl(),
        current,
        ADVICE);
  }

  /** Body {@code {"templateId": "<uuid>"}}. Applying the active template again changes nothing. */
  public AppliedPlan apply(UUID userId, JsonNode body) {
    JsonNode value = body == null ? null : body.get("templateId");
    UUID id = templateId(value != null && value.isTextual() ? value.asText() : null);
    requireConsent(userId);
    eligible(userId, id);
    PlanWrite write;
    try {
      write = repository.applyPlan(userId, id, LocalDate.now(clock), clock.instant());
    } catch (DuplicateKeyException e) {
      throw LifestyleErrors.planConflict();
    }
    ActivePlan plan = repository.findActivePlan(userId).orElseThrow(LifestyleErrors::planConflict);
    return new AppliedPlan(
        plan.id().toString(),
        plan.templateId().toString(),
        plan.templateTitle(),
        plan.startedOn(),
        write.changed(),
        write.previousPlanId() == null ? null : write.previousPlanId().toString(),
        write.remindersTurnedOff());
  }

  private void requireConsent(UUID userId) {
    if (!consents.healthDataProcessingAccepted(userId)) throw LifestyleErrors.consentRequired();
  }

  /** The approved template, checked against the user's allergies and profile (A1, A2). */
  private TemplateRow eligible(UUID userId, UUID id) {
    TemplateRow template =
        repository.approvedTemplate(id, includeSynthetic).orElseThrow(LifestyleErrors::templateNotFound);
    Optional<Set<String>> allergies = profiles.foodAllergies(userId);
    if (template.requiresProfile() && allergies.isEmpty()) throw LifestyleErrors.profileRequired();
    if (containsAllergen(template, allergies.orElse(Set.of()))) throw LifestyleErrors.templateHasAllergen();
    return template;
  }

  /** A1: without a profile only templates that allow it are offered. A2: never one with an allergen. */
  private static boolean offerable(TemplateRow template, Optional<Set<String>> allergies) {
    if (allergies.isEmpty()) return !template.requiresProfile();
    return !containsAllergen(template, allergies.get());
  }

  private static boolean containsAllergen(TemplateRow template, Set<String> allergies) {
    return template.excludedAllergens().stream()
        .map(HealthProfileQuery::normaliseAllergen)
        .anyMatch(allergies::contains);
  }

  private TemplateSummary summary(TemplateRow template) {
    return new TemplateSummary(
        template.id().toString(),
        template.title(),
        template.goal(),
        template.summary(),
        template.durationDays(),
        activities(template).size(),
        template.notSuitableFor(),
        template.sourceUrl());
  }

  private List<PlanActivity> activities(TemplateRow template) {
    return PlanActivity.parse(json, template.activitiesJson(), template.id());
  }

  private static UUID templateId(String value) {
    try {
      return UUID.fromString(value == null ? "" : value.strip());
    } catch (IllegalArgumentException e) {
      throw LifestyleErrors.invalidTemplateId();
    }
  }
}
