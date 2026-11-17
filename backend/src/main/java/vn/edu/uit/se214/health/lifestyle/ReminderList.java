package vn.edu.uit.se214.health.lifestyle;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * GET /lifestyle/reminders. {@code availableChannels}: channels this user can turn on now (A2:
 * the client offers only these).
 */
public record ReminderList(List<Reminder> reminders, List<ReminderChannel.Type> availableChannels) {

  /**
   * One reminder. Times are ISO-8601 instants; {@code localTime} is HH:mm (24 h) in {@code
   * timezone}. {@code nextSendAt} is null when the reminder is off. {@code disabledReason} is
   * {@code PLAN_SWITCH} when a plan switch paused it, else null. {@code lastDelivery} is the latest
   * scheduled send: PENDING, SENT or FAILED.
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Reminder(
      String id,
      String content,
      String activityId,
      String localTime,
      List<Integer> daysOfWeek,
      String timezone,
      ReminderChannel.Type channel,
      boolean enabled,
      String disabledReason,
      String nextSendAt,
      Delivery lastDelivery) {}

  public record Delivery(String scheduledFor, String status, String sentAt) {}
}
