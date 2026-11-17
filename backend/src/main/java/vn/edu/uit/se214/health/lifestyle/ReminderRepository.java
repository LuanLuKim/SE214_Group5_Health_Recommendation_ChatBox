package vn.edu.uit.se214.health.lifestyle;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SQL for lifestyle.reminders and lifestyle.reminder_deliveries (UC-14). Every query a user can
 * reach filters by {@code user_id}. Values are always bound, never concatenated.
 */
public class ReminderRepository {
  private static final String COLUMNS =
      """
      r.id, r.user_id, r.content, r.activity_id, r.local_time, r.days_of_week, r.timezone,
      r.channel, r.enabled, r.disabled_reason, r.updated_at, d.scheduled_for, d.status, d.sent_at""";
  private static final String LAST_DELIVERY =
      """
      LEFT JOIN LATERAL (
        SELECT scheduled_for, status, sent_at FROM lifestyle.reminder_deliveries
        WHERE reminder_id = r.id
        ORDER BY scheduled_for DESC
        LIMIT 1
      ) d ON true""";

  private final JdbcClient jdbc;

  public ReminderRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Values a user sets; the service has validated them. */
  record Fields(
      String content,
      String activityId,
      LocalTime localTime,
      List<Integer> daysOfWeek,
      ZoneId zone,
      ReminderChannel.Type channel,
      boolean enabled) {}

  List<ReminderRow> list(UUID userId) {
    return jdbc.sql(
            "SELECT " + COLUMNS + "\nFROM lifestyle.reminders r\n" + LAST_DELIVERY
                + "\nWHERE r.user_id = ?\nORDER BY r.local_time, r.created_at, r.id")
        .param(userId)
        .query((rs, row) -> reminder(rs))
        .list();
  }

  Optional<ReminderRow> find(UUID userId, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + "\nFROM lifestyle.reminders r\n" + LAST_DELIVERY
                + "\nWHERE r.id = ? AND r.user_id = ?")
        .params(id, userId)
        .query((rs, row) -> reminder(rs))
        .optional();
  }

  /** Reminders that count towards the per-user limit: all but those paused by a plan switch. */
  int count(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM lifestyle.reminders WHERE user_id = ? AND disabled_reason IS NULL")
        .param(userId)
        .query(Integer.class)
        .single();
  }

  void insert(UUID id, UUID userId, Fields fields, Instant now) {
    jdbc.sql(
            """
            INSERT INTO lifestyle.reminders
              (id, user_id, content, activity_id, local_time, days_of_week, timezone, channel, enabled,
               created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?::smallint[], ?, ?, ?, ?, ?)
            """)
        .params(
            id,
            userId,
            fields.content(),
            fields.activityId(),
            fields.localTime(),
            days(fields.daysOfWeek()),
            fields.zone().getId(),
            fields.channel().name(),
            fields.enabled(),
            timestamp(now),
            timestamp(now))
        .update();
  }

  /**
   * False when the reminder does not exist or belongs to someone else. Turning it on clears the
   * reason it was off; a reminder paused by a plan switch that stays off keeps its reason.
   */
  boolean update(UUID userId, UUID id, Fields fields, Instant now) {
    return jdbc.sql(
                """
                UPDATE lifestyle.reminders
                SET content = ?, activity_id = ?, local_time = ?, days_of_week = ?::smallint[],
                    timezone = ?, channel = ?, enabled = ?, updated_at = ?,
                    disabled_reason = CASE WHEN ? THEN NULL ELSE disabled_reason END
                WHERE id = ? AND user_id = ?
                """)
            .params(
                fields.content(),
                fields.activityId(),
                fields.localTime(),
                days(fields.daysOfWeek()),
                fields.zone().getId(),
                fields.channel().name(),
                fields.enabled(),
                timestamp(now),
                fields.enabled(),
                id,
                userId)
            .update()
        == 1;
  }

  /** Deliveries go with it (ON DELETE CASCADE). */
  boolean delete(UUID userId, UUID id) {
    return jdbc.sql("DELETE FROM lifestyle.reminders WHERE id = ? AND user_id = ?")
            .params(id, userId)
            .update()
        == 1;
  }

  /** Worker: every reminder that is on. */
  List<ReminderRow> enabled() {
    return jdbc.sql(
            "SELECT " + COLUMNS + "\nFROM lifestyle.reminders r\n" + LAST_DELIVERY + "\nWHERE r.enabled")
        .query((rs, row) -> reminder(rs))
        .list();
  }

  /**
   * Reserves one send of {@code reminderId} at {@code scheduledFor}. UNIQUE (reminder_id,
   * scheduled_for) lets exactly one worker run win; the others get empty and send nothing.
   */
  Optional<UUID> claim(UUID reminderId, Instant scheduledFor, ReminderChannel.Type channel, Instant now) {
    return jdbc.sql(
            """
            INSERT INTO lifestyle.reminder_deliveries
              (id, reminder_id, scheduled_for, channel, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'PENDING', ?, ?)
            ON CONFLICT (reminder_id, scheduled_for) DO NOTHING
            RETURNING id
            """)
        .params(UUID.randomUUID(), reminderId, timestamp(scheduledFor), channel.name(), timestamp(now), timestamp(now))
        .query(UUID.class)
        .optional();
  }

  /** A failed send that may be tried again, with what is needed to send it. */
  record Retry(UUID deliveryId, UUID userId, String content, ReminderChannel.Type channel) {}

  /**
   * A3: failed sends of reminders that are still on, scheduled at or after {@code since}, with
   * fewer than {@code maxAttempts} attempts.
   */
  List<Retry> retryable(int maxAttempts, Instant since) {
    return jdbc.sql(
            """
            SELECT d.id, r.user_id, r.content, d.channel
            FROM lifestyle.reminder_deliveries d
            JOIN lifestyle.reminders r ON r.id = d.reminder_id
            WHERE d.status = 'FAILED' AND d.attempt_count < ? AND d.scheduled_for >= ? AND r.enabled
            ORDER BY d.scheduled_for, d.id
            """)
        .params(maxAttempts, timestamp(since))
        .query(
            (rs, row) ->
                new Retry(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getString("content"),
                    ReminderChannel.Type.valueOf(rs.getString("channel"))))
        .list();
  }

  /** Takes a failed send back to PENDING on the same row; only one worker run can win it. */
  boolean claimRetry(UUID deliveryId, int maxAttempts, Instant now) {
    return jdbc.sql(
                """
                UPDATE lifestyle.reminder_deliveries SET status = 'PENDING', updated_at = ?
                WHERE id = ? AND status = 'FAILED' AND attempt_count < ?
                """)
            .params(timestamp(now), deliveryId, maxAttempts)
            .update()
        == 1;
  }

  void markSent(UUID deliveryId, Instant now) {
    jdbc.sql(
            """
            UPDATE lifestyle.reminder_deliveries
            SET status = 'SENT', sent_at = ?, attempt_count = attempt_count + 1, last_error_code = NULL,
                updated_at = ?
            WHERE id = ?
            """)
        .params(timestamp(now), timestamp(now), deliveryId)
        .update();
  }

  /** Only a short code is stored: no recipient, payload or provider message. */
  void markFailed(UUID deliveryId, String errorCode, Instant now) {
    jdbc.sql(
            """
            UPDATE lifestyle.reminder_deliveries
            SET status = 'FAILED', attempt_count = attempt_count + 1, last_error_code = ?, updated_at = ?
            WHERE id = ?
            """)
        .params(errorCode, timestamp(now), deliveryId)
        .update();
  }

  private static ReminderRow reminder(ResultSet rs) throws SQLException {
    OffsetDateTime scheduledFor = rs.getObject("scheduled_for", OffsetDateTime.class);
    OffsetDateTime sentAt = rs.getObject("sent_at", OffsetDateTime.class);
    return new ReminderRow(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("content"),
        rs.getString("activity_id"),
        rs.getObject("local_time", LocalTime.class),
        integers(rs.getArray("days_of_week")),
        ZoneId.of(rs.getString("timezone")),
        ReminderChannel.Type.valueOf(rs.getString("channel")),
        rs.getBoolean("enabled"),
        rs.getString("disabled_reason"),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
        scheduledFor == null
            ? null
            : new ReminderRow.LastDelivery(
                scheduledFor.toInstant(), rs.getString("status"), sentAt == null ? null : sentAt.toInstant()));
  }

  private static List<Integer> integers(Array array) throws SQLException {
    if (array == null) return List.of();
    return Arrays.stream((Object[]) array.getArray()).map(v -> ((Number) v).intValue()).toList();
  }

  /** PostgreSQL array literal of validated numbers 1–7, for a {@code ?::smallint[]} parameter. */
  private static String days(List<Integer> days) {
    return "{" + String.join(",", days.stream().map(String::valueOf).toList()) + "}";
  }

  private static OffsetDateTime timestamp(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
