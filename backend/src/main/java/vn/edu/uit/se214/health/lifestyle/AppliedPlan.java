package vn.edu.uit.se214.health.lifestyle;

import java.time.LocalDate;
import java.util.List;

/**
 * UC-12 answer to {@code PUT /api/v1/lifestyle/plan}, read back after the write.
 *
 * @param changed false when the template was already the active plan (nothing was written)
 * @param previousPlanId the plan that was ended by this switch, or null
 * @param remindersTurnedOff UC-14 reminders turned off because they follow an activity the new plan
 *     does not have (0 when nothing changed)
 * @param restorableReminderIds reminders paused by an earlier switch whose activity this plan has
 *     again; they stay off until the user turns them on (the client asks)
 */
public record AppliedPlan(
    String planId,
    String templateId,
    String templateTitle,
    LocalDate startedOn,
    boolean changed,
    String previousPlanId,
    int remindersTurnedOff,
    List<String> restorableReminderIds) {}
