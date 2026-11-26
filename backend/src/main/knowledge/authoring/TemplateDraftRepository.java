package knowledge.authoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import knowledge.authoring.TemplateDraftInput.Activity;
import shared.domain.ReviewStatus;

/**
 * SQL for UC-15 plan template drafts in lifestyle.templates (the table M6 reads approved templates from).
 * A template that a plan of M6 uses is never deleted.
 */
public class TemplateDraftRepository implements DraftStore<TemplateDraftInput, TemplateDraft> {
  private static final String FIELDS =
      """
      id, title, goal, summary, activities::text AS activities, duration_days, not_suitable_for,
      excluded_allergens, requires_profile, applicability, advisory, source_name, source_url,
      source_updated_at, copyright_notes, verified_at, review_status, created_by, updated_by, created_at,
      updated_at, revision, submitted_at, submitted_by""";
  private static final String EDITABLE = "review_status IN ('DRAFT', 'RETURNED')";
  private static final TypeReference<List<Activity>> ACTIVITIES = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public TemplateDraftRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public TemplateDraft create(TemplateDraftInput input, UUID actor) {
    return jdbc.sql(
            """
            INSERT INTO lifestyle.templates
              (id, title, goal, summary, activities, duration_days, not_suitable_for, excluded_allergens,
               requires_profile, applicability, advisory, source_name, source_url, source_updated_at,
               copyright_notes, created_by, updated_by)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING
            """
                + FIELDS)
        .params(UUID.randomUUID(), input.title(), input.goal(), input.summary(), toJson(input.activities()),
            input.durationDays(), array(input.notSuitableFor()), array(input.excludedAllergens()),
            input.requiresProfile(), input.applicability(), input.advisory(), input.sourceName(),
            input.sourceUrl(), DraftFields.timestamp(input.sourceUpdatedAt()), input.copyrightNotes(), actor,
            actor)
        .query(this::map)
        .single();
  }

  @Override
  public List<TemplateDraft> list() {
    return jdbc.sql("SELECT " + FIELDS + " FROM lifestyle.templates ORDER BY created_at DESC, id")
        .query(this::map)
        .list();
  }

  @Override
  public Optional<TemplateDraft> get(UUID id) {
    return jdbc.sql("SELECT " + FIELDS + " FROM lifestyle.templates WHERE id = ?")
        .param(id)
        .query(this::map)
        .optional();
  }

  @Override
  public Optional<TemplateDraft> update(UUID id, TemplateDraftInput input, UUID actor, int revision) {
    return jdbc.sql(
            """
            UPDATE lifestyle.templates SET
              title = ?, goal = ?, summary = ?, activities = ?::jsonb, duration_days = ?, not_suitable_for = ?,
              excluded_allergens = ?, requires_profile = ?, applicability = ?, advisory = ?, source_name = ?,
              source_url = ?, source_updated_at = ?, copyright_notes = ?, updated_by = ?,
              review_status = 'DRAFT', verified_at = NULL, updated_at = now(), revision = revision + 1,
              submitted_at = NULL, submitted_by = NULL
            WHERE id = ? AND revision = ?"""
                + " AND " + EDITABLE + " RETURNING " + FIELDS)
        .params(input.title(), input.goal(), input.summary(), toJson(input.activities()), input.durationDays(),
            array(input.notSuitableFor()), array(input.excludedAllergens()), input.requiresProfile(),
            input.applicability(), input.advisory(), input.sourceName(), input.sourceUrl(),
            DraftFields.timestamp(input.sourceUpdatedAt()), input.copyrightNotes(), actor, id, revision)
        .query(this::map)
        .optional();
  }

  @Override
  public Optional<TemplateDraft> submit(UUID id, UUID actor, int revision) {
    return jdbc.sql(
            """
            UPDATE lifestyle.templates SET
              review_status = 'PENDING_REVIEW', verified_at = NULL, submitted_at = now(), submitted_by = ?,
              updated_by = ?, updated_at = now(), revision = revision + 1
            WHERE id = ? AND revision = ?"""
                + " AND " + EDITABLE + " RETURNING " + FIELDS)
        .params(actor, actor, id, revision)
        .query(this::map)
        .optional();
  }

  @Override
  public boolean delete(UUID id) {
    return jdbc.sql(
            "DELETE FROM lifestyle.templates WHERE id = ? AND " + EDITABLE
                + " AND NOT EXISTS (SELECT 1 FROM lifestyle.plans WHERE template_id = ?)")
        .params(id, id)
        .update()
        > 0;
  }

  private TemplateDraft map(ResultSet rs, int row) throws SQLException {
    Object duration = rs.getObject("duration_days");
    return new TemplateDraft(
        rs.getString("id"),
        rs.getString("title"),
        rs.getString("goal"),
        rs.getString("summary"),
        activities(rs.getString("activities")),
        duration == null ? null : ((Number) duration).intValue(),
        strings(rs.getArray("not_suitable_for")),
        strings(rs.getArray("excluded_allergens")),
        rs.getBoolean("requires_profile"),
        rs.getString("applicability"),
        rs.getString("advisory"),
        rs.getString("source_name"),
        rs.getString("source_url"),
        ArticleDraftRepository.time(rs, "source_updated_at"),
        rs.getString("copyright_notes"),
        ArticleDraftRepository.time(rs, "verified_at"),
        ReviewStatus.valueOf(rs.getString("review_status")),
        rs.getString("created_by"),
        rs.getString("updated_by"),
        ArticleDraftRepository.time(rs, "created_at"),
        ArticleDraftRepository.time(rs, "updated_at"),
        rs.getInt("revision"),
        ArticleDraftRepository.time(rs, "submitted_at"),
        rs.getString("submitted_by"));
  }

  /** Activities of a stored template; JSON an earlier writer left unreadable reads as none (must be redone). */
  private List<Activity> activities(String value) {
    try {
      List<Activity> parsed = value == null ? null : json.readValue(value, ACTIVITIES);
      return parsed == null ? List.of() : List.copyOf(parsed.stream().filter(a -> a != null).toList());
    } catch (JsonProcessingException | IllegalArgumentException e) {
      return List.of();
    }
  }

  private String toJson(List<Activity> activities) {
    try {
      return json.writeValueAsString(activities);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Activities cannot be written as JSON", e);
    }
  }

  private static String[] array(List<String> values) {
    return values.toArray(String[]::new);
  }

  private static List<String> strings(Array array) throws SQLException {
    return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
  }
}
