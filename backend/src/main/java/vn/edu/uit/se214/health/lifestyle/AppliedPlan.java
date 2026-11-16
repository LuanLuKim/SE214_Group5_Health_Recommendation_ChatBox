package vn.edu.uit.se214.health.lifestyle;

import java.time.LocalDate;

/**
 * UC-12 answer to {@code PUT /api/v1/lifestyle/plan}, read back after the write.
 *
 * @param changed false when the template was already the active plan (nothing was written)
 * @param previousPlanId the plan that was ended by this switch, or null
 */
public record AppliedPlan(
    String planId,
    String templateId,
    String templateTitle,
    LocalDate startedOn,
    boolean changed,
    String previousPlanId) {}
