package vn.edu.uit.se214.health.lifestyle;

import java.util.List;
import java.util.UUID;

/** A lifestyle.templates row as stored; eligibility is decided by {@link LifestylePlanService}. */
record TemplateRow(
    UUID id,
    String title,
    LifestyleGoal goal,
    String summary,
    Integer durationDays,
    String activitiesJson,
    List<String> excludedAllergens,
    List<String> notSuitableFor,
    boolean requiresProfile,
    String sourceUrl) {}
