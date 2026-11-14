package vn.edu.uit.se214.health.lifestyle;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.edu.uit.se214.health.lifestyle.ProgressCalculator.Completion;

/** SQL for lifestyle.plans, lifestyle.templates and lifestyle.completions. Values are always bound, never concatenated. */
public class LifestyleRepository {
  private final JdbcClient jdbc;

  public LifestyleRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The user's plan with its template. lifestyle.plans allows one plan per user. */
  public Optional<ActivePlan> findActivePlan(UUID userId) {
    return jdbc.sql(
            """
            SELECT p.id, p.template_id, p.started_on, t.title, t.activities::text AS activities
            FROM lifestyle.plans p
            JOIN lifestyle.templates t ON t.id = p.template_id
            WHERE p.user_id = ?
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
}
