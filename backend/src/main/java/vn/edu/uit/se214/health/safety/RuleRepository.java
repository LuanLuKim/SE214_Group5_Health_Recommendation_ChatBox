package vn.edu.uit.se214.health.safety;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.edu.uit.se214.health.safety.api.CareLevel;
import vn.edu.uit.se214.health.shared.domain.Instants;
import vn.edu.uit.se214.health.shared.domain.ReviewStatus;

/** Parameterized SQL for safety.rules. Only this module reads or writes its schema. */
public class RuleRepository {
  private static final String FIELDS =
      """
      id, name, conditions, required_fields, care_level, specialty_ids,
      source_name, source_url, review_status, is_synthetic, created_at, updated_at""";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public RuleRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public SafetyRule create(RuleDraftInput input) {
    return jdbc.sql(
            """
            INSERT INTO safety.rules
              (id, name, conditions, required_fields, care_level, specialty_ids, source_name, source_url)
            VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            RETURNING
            """
                + FIELDS)
        .params(
            UUID.randomUUID(),
            input.name(),
            toJson(input.conditions()),
            input.requiredFields().toArray(String[]::new),
            input.careLevel().name(),
            input.specialtyIds().toArray(String[]::new),
            input.sourceName(),
            input.sourceUrl())
        .query(this::map)
        .single();
  }

  public List<SafetyRule> list() {
    return jdbc.sql("SELECT " + FIELDS + " FROM safety.rules ORDER BY created_at DESC, id")
        .query(this::map)
        .list();
  }

  /**
   * The only read the Safety Gate may load rules from (UC-07): APPROVED rules with at least one
   * required condition. Drafts, pending, returned and retired rules never reach evaluation, and a
   * rule without conditions is skipped so it can never match every input.
   */
  public List<SafetyRule> listApproved() {
    return jdbc.sql(
            "SELECT "
                + FIELDS
                + """
                 FROM safety.rules
                WHERE review_status = 'APPROVED'
                  AND jsonb_typeof(conditions -> 'all') = 'array'
                  AND jsonb_array_length(conditions -> 'all') > 0
                ORDER BY created_at, id
                """)
        .query(this::map)
        .list();
  }

  public Optional<SafetyRule> get(UUID id) {
    return jdbc.sql("SELECT " + FIELDS + " FROM safety.rules WHERE id = ?")
        .param(id)
        .query(this::map)
        .optional();
  }

  /** Only DRAFT and RETURNED rules can be edited; an edited RETURNED rule goes back to DRAFT. */
  public Optional<SafetyRule> update(UUID id, RuleDraftInput input) {
    return jdbc.sql(
            """
            UPDATE safety.rules SET
              name = ?, conditions = ?::jsonb, required_fields = ?, care_level = ?,
              specialty_ids = ?, source_name = ?, source_url = ?,
              review_status = 'DRAFT', updated_at = now()
            WHERE id = ? AND review_status IN ('DRAFT', 'RETURNED')
            RETURNING
            """
                + FIELDS)
        .params(
            input.name(),
            toJson(input.conditions()),
            input.requiredFields().toArray(String[]::new),
            input.careLevel().name(),
            input.specialtyIds().toArray(String[]::new),
            input.sourceName(),
            input.sourceUrl(),
            id)
        .query(this::map)
        .optional();
  }

  /** Only an unchanged editable rule can enter review; submission never activates it. */
  public Optional<SafetyRule> submit(UUID id, String sourceName, String sourceUrl) {
    return jdbc.sql(
            """
            UPDATE safety.rules SET review_status = 'PENDING_REVIEW', updated_at = now()
            WHERE id = ? AND review_status IN ('DRAFT', 'RETURNED')
              AND source_name = ? AND source_url = ?
            RETURNING
            """
                + FIELDS)
        .params(id, sourceName, sourceUrl)
        .query(this::map)
        .optional();
  }

  private SafetyRule map(ResultSet rs, int row) throws SQLException {
    return new SafetyRule(
        rs.getString("id"),
        rs.getString("name"),
        fromJson(rs.getString("conditions")),
        strings(rs.getArray("required_fields")),
        CareLevel.valueOf(rs.getString("care_level")),
        strings(rs.getArray("specialty_ids")),
        rs.getString("source_name"),
        rs.getString("source_url"),
        ReviewStatus.valueOf(rs.getString("review_status")),
        rs.getBoolean("is_synthetic"),
        Instants.iso(rs.getObject("created_at", OffsetDateTime.class)),
        Instants.iso(rs.getObject("updated_at", OffsetDateTime.class)));
  }

  private static List<String> strings(Array array) throws SQLException {
    return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
  }

  private String toJson(RuleDraftInput.Conditions conditions) {
    try {
      return json.writeValueAsString(conditions);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize rule conditions");
    }
  }

  private RuleDraftInput.Conditions fromJson(String value) {
    try {
      return json.readValue(value, RuleDraftInput.Conditions.class);
    } catch (JsonProcessingException e) {
      // Legacy synthetic fixtures may use another shape; they are never evaluated.
      return new RuleDraftInput.Conditions(List.of(), List.of());
    }
  }
}
