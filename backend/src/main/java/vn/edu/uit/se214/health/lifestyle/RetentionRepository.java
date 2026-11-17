package vn.edu.uit.se214.health.lifestyle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * SQL of the M6 cleanup job (SCRUM-164). Every statement handles at most {@code limit} rows and
 * skips rows another run has locked, so several backend instances may run it at once.
 */
public class RetentionRepository {
  private final JdbcClient jdbc;
  private final TransactionOperations transactions;

  public RetentionRepository(JdbcClient jdbc, TransactionOperations transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  /** Deliveries in one of {@code statuses} scheduled before {@code before}. */
  int deleteDeliveries(List<String> statuses, Instant before, int limit) {
    return jdbc.sql(
            """
            DELETE FROM lifestyle.reminder_deliveries WHERE id IN (
              SELECT id FROM lifestyle.reminder_deliveries
              WHERE status = ANY (?::text[]) AND scheduled_for < ?
              ORDER BY scheduled_for, id
              LIMIT ?
              FOR UPDATE SKIP LOCKED)
            """)
        .params("{" + String.join(",", statuses) + "}", timestamp(before), limit)
        .update();
  }

  /** Reminders paused by a plan switch and unchanged since {@code before}; deliveries go with them. */
  int deletePausedReminders(Instant before, int limit) {
    return jdbc.sql(
            """
            DELETE FROM lifestyle.reminders WHERE id IN (
              SELECT id FROM lifestyle.reminders
              WHERE disabled_reason = 'PLAN_SWITCH' AND NOT enabled AND updated_at < ?
              ORDER BY updated_at, id
              LIMIT ?
              FOR UPDATE SKIP LOCKED)
            """)
        .params(timestamp(before), limit)
        .update();
  }

  /**
   * Plans ended before {@code endedBefore} whose completions are still detailed: keeps the counts
   * on the plan, then removes the completion rows, in one transaction. Returns the plans handled.
   */
  int summarizeEndedPlans(LocalDate endedBefore, Instant now, int limit) {
    Integer handled =
        transactions.execute(
            status -> {
              List<UUID> plans =
                  jdbc.sql(
                          """
                          SELECT id FROM lifestyle.plans
                          WHERE ended_on IS NOT NULL AND ended_on < ? AND completions_summarized_at IS NULL
                          ORDER BY ended_on, id
                          LIMIT ?
                          FOR UPDATE SKIP LOCKED
                          """)
                      .params(endedBefore, limit)
                      .query(UUID.class)
                      .list();
              if (plans.isEmpty()) return 0;
              String ids = "{" + String.join(",", plans.stream().map(UUID::toString).toList()) + "}";
              jdbc.sql(
                      """
                      UPDATE lifestyle.plans p SET
                        completion_count =
                          (SELECT count(*) FROM lifestyle.completions c WHERE c.plan_id = p.id),
                        completed_days =
                          (SELECT count(DISTINCT c.completed_on) FROM lifestyle.completions c WHERE c.plan_id = p.id),
                        completions_summarized_at = ?
                      WHERE p.id = ANY (?::uuid[])
                      """)
                  .params(timestamp(now), ids)
                  .update();
              jdbc.sql("DELETE FROM lifestyle.completions WHERE plan_id = ANY (?::uuid[])")
                  .param(ids)
                  .update();
              return plans.size();
            });
    return handled == null ? 0 : handled;
  }

  private static OffsetDateTime timestamp(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
