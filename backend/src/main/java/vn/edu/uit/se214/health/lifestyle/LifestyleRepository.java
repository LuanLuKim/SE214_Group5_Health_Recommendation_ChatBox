package vn.edu.uit.se214.health.lifestyle;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import vn.edu.uit.se214.health.lifestyle.ProgressCalculator.Completion;

/** SQL for lifestyle.plans, lifestyle.templates and lifestyle.completions. Values are always bound, never concatenated. */
public class LifestyleRepository {
  private static final String TEMPLATE_COLUMNS =
      """
      id, title, goal, summary, duration_days, activities::text AS activities,
      excluded_allergens, not_suitable_for, requires_profile, source_url""";

  private final JdbcClient jdbc;
  private final TransactionOperations transactions;

  /** {@code transactions} wraps the plan switch (end the old plan, start the new one) in one transaction. */
  public LifestyleRepository(JdbcClient jdbc, TransactionOperations transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  /** The user's active plan (ended_on IS NULL) with its template; the partial unique index allows one. */
  public Optional<ActivePlan> findActivePlan(UUID userId) {
    return jdbc.sql(
            """
            SELECT p.id, p.template_id, p.started_on, t.title, t.activities::text AS activities
            FROM lifestyle.plans p
            JOIN lifestyle.templates t ON t.id = p.template_id
            WHERE p.user_id = ? AND p.ended_on IS NULL
            """)
        .param(userId)
        .query(
            (rs, row) ->
                new ActivePlan(
                    rs.getObject("id", UUID.class),
                    rs.getObject("template_id", UUID.class),
                    rs.getString("title"),
                    rs.getObject("started_on", LocalDate.class),
                    rs.getString("activities")))
        .optional();
  }

  /**
   * UC-12: approved templates, optionally of one goal. Synthetic templates are included only when
   * {@code includeSynthetic} (synthetic seeding, never in production).
   */
  public List<TemplateRow> approvedTemplates(Optional<LifestyleGoal> goal, boolean includeSynthetic) {
    String goalName = goal.map(Enum::name).orElse(null);
    return jdbc.sql(
            "SELECT "
                + TEMPLATE_COLUMNS
                + """

                FROM lifestyle.templates
                WHERE review_status = 'APPROVED' AND (? OR NOT is_synthetic)
                  AND (CAST(? AS text) IS NULL OR goal = CAST(? AS text))
                ORDER BY title, id
                """)
        .params(includeSynthetic, goalName, goalName)
        .query((rs, row) -> template(rs))
        .list();
  }

  public Optional<TemplateRow> approvedTemplate(UUID id, boolean includeSynthetic) {
    return jdbc.sql(
            "SELECT "
                + TEMPLATE_COLUMNS
                + """

                FROM lifestyle.templates
                WHERE id = ? AND review_status = 'APPROVED' AND (? OR NOT is_synthetic)
                """)
        .params(id, includeSynthetic)
        .query((rs, row) -> template(rs))
        .optional();
  }

  /**
   * Result of {@link #applyPlan}. {@code previousPlanId} is the plan ended by a switch, or null;
   * {@code remindersTurnedOff} counts the UC-14 reminders turned off by the switch;
   * {@code restorableReminderIds} are reminders paused by an earlier switch whose activity the new
   * plan has again. They stay off until the user turns them on.
   */
  public record PlanWrite(
      UUID planId,
      boolean changed,
      UUID previousPlanId,
      int remindersTurnedOff,
      List<UUID> restorableReminderIds) {}

  /**
   * UC-12: make {@code templateId} the user's active plan from {@code today}. Applying the template
   * that is already active writes nothing. Switching ends the old plan on {@code today} and keeps
   * its completions. A concurrent switch fails on the partial unique index instead of leaving two
   * active plans.
   *
   * <p>In the same transaction, reminders that are on and follow an activity the new template does
   * not have are paused (UC-14): turned off with reason PLAN_SWITCH, so they stop being sent. Their
   * past deliveries stay as they are. Reminders without an activity, or whose activity id the new
   * template also has, keep going. Paused reminders that follow an activity of the new template are
   * returned, not turned on.
   */
  public PlanWrite applyPlan(UUID userId, UUID templateId, LocalDate today, Instant now) {
    return transactions.execute(
        status -> {
          Optional<CurrentPlan> current =
              jdbc.sql(
                      """
                      SELECT id, template_id FROM lifestyle.plans
                      WHERE user_id = ? AND ended_on IS NULL
                      FOR UPDATE
                      """)
                  .param(userId)
                  .query(
                      (rs, row) ->
                          new CurrentPlan(rs.getObject("id", UUID.class), rs.getObject("template_id", UUID.class)))
                  .optional();
          if (current.isPresent() && current.get().templateId().equals(templateId))
            return new PlanWrite(current.get().id(), false, null, 0, List.of());
          current.ifPresent(
              plan ->
                  jdbc.sql("UPDATE lifestyle.plans SET ended_on = GREATEST(?, started_on) WHERE id = ?")
                      .params(today, plan.id())
                      .update());
          UUID planId = UUID.randomUUID();
          jdbc.sql("INSERT INTO lifestyle.plans (id, user_id, template_id, started_on) VALUES (?, ?, ?, ?)")
              .params(planId, userId, templateId, today)
              .update();
          int turnedOff =
              jdbc.sql(
                      """
                      UPDATE lifestyle.reminders r
                      SET enabled = false, disabled_reason = 'PLAN_SWITCH', updated_at = ?
                      WHERE r.user_id = ? AND r.enabled AND r.activity_id IS NOT NULL
                        AND NOT EXISTS (
                          SELECT 1 FROM lifestyle.templates t
                          CROSS JOIN LATERAL jsonb_array_elements(
                            CASE WHEN jsonb_typeof(t.activities) = 'array' THEN t.activities ELSE '[]'::jsonb END
                          ) AS a(activity)
                          WHERE t.id = ? AND a.activity ->> 'id' = r.activity_id)
                      """)
                  .params(OffsetDateTime.ofInstant(now, ZoneOffset.UTC), userId, templateId)
                  .update();
          List<UUID> restorable =
              jdbc.sql(
                      """
                      SELECT r.id FROM lifestyle.reminders r
                      WHERE r.user_id = ? AND r.disabled_reason = 'PLAN_SWITCH'
                        AND EXISTS (
                          SELECT 1 FROM lifestyle.templates t
                          CROSS JOIN LATERAL jsonb_array_elements(
                            CASE WHEN jsonb_typeof(t.activities) = 'array' THEN t.activities ELSE '[]'::jsonb END
                          ) AS a(activity)
                          WHERE t.id = ? AND a.activity ->> 'id' = r.activity_id)
                      ORDER BY r.local_time, r.created_at, r.id
                      """)
                  .params(userId, templateId)
                  .query(UUID.class)
                  .list();
          return new PlanWrite(
              planId, true, current.map(CurrentPlan::id).orElse(null), turnedOff, restorable);
        });
  }

  private record CurrentPlan(UUID id, UUID templateId) {}

  /** UC-13 A2: the primary key (plan, activity, day) keeps one row however often it is sent. */
  public boolean insertCompletion(UUID planId, String activityId, LocalDate day) {
    return jdbc.sql(
                """
                INSERT INTO lifestyle.completions (plan_id, activity_id, completed_on)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """)
            .params(planId, activityId, day)
            .update()
        == 1;
  }

  public boolean deleteCompletion(UUID planId, String activityId, LocalDate day) {
    return jdbc.sql(
                "DELETE FROM lifestyle.completions WHERE plan_id = ? AND activity_id = ? AND completed_on = ?")
            .params(planId, activityId, day)
            .update()
        == 1;
  }

  public List<Completion> completions(UUID planId, LocalDate from, LocalDate to) {
    return jdbc.sql(
            """
            SELECT activity_id, completed_on FROM lifestyle.completions
            WHERE plan_id = ? AND completed_on BETWEEN ? AND ?
            """)
        .params(planId, from, to)
        .query(
            (rs, row) ->
                new Completion(
                    planId.toString(),
                    rs.getString("activity_id"),
                    rs.getObject("completed_on", LocalDate.class)))
        .list();
  }

  private static TemplateRow template(ResultSet rs) throws SQLException {
    return new TemplateRow(
        rs.getObject("id", UUID.class),
        rs.getString("title"),
        LifestyleGoal.valueOf(rs.getString("goal")),
        rs.getString("summary"),
        rs.getObject("duration_days", Integer.class),
        rs.getString("activities"),
        strings(rs.getArray("excluded_allergens")),
        strings(rs.getArray("not_suitable_for")),
        rs.getBoolean("requires_profile"),
        rs.getString("source_url"));
  }

  private static List<String> strings(Array array) throws SQLException {
    return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
  }
}
