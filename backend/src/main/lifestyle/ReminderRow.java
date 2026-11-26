package lifestyle;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * A row of lifestyle.reminders. {@code daysOfWeek} uses ISO numbers (1 = Monday … 7 = Sunday).
 * {@code updatedAt} is when the schedule was last saved: an occurrence before it is never sent.
 * {@code disabledReason} is {@code PLAN_SWITCH} when a plan switch turned the reminder off, else null.
 */
record ReminderRow(
    UUID id,
    UUID userId,
    String content,
    String activityId,
    LocalTime localTime,
    List<Integer> daysOfWeek,
    ZoneId zone,
    ReminderChannel.Type channel,
    boolean enabled,
    String disabledReason,
    Instant updatedAt,
    LastDelivery lastDelivery) {

  /** The most recent row of lifestyle.reminder_deliveries, or null when nothing was scheduled yet. */
  record LastDelivery(Instant scheduledFor, String status, Instant sentAt) {}
}
