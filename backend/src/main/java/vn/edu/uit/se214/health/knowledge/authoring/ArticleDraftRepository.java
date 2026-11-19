package vn.edu.uit.se214.health.knowledge.authoring;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.edu.uit.se214.health.shared.domain.Instants;
import vn.edu.uit.se214.health.shared.domain.ReviewStatus;

/** SQL for UC-15 article drafts in knowledge.articles. Values are always bound, never concatenated. */
public class ArticleDraftRepository implements DraftStore<ArticleDraftInput, ArticleDraft> {
  private static final String FIELDS =
      """
      id, title, topic, content, source_name, source_url, source_updated_at, copyright_notes,
      verified_at, review_status, created_by, updated_by, created_at, updated_at,
      revision, submitted_at, submitted_by""";
  private static final String EDITABLE = "review_status IN ('DRAFT', 'RETURNED')";

  private final JdbcClient jdbc;

  public ArticleDraftRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public ArticleDraft create(ArticleDraftInput input, UUID actor) {
    return jdbc.sql(
            """
            INSERT INTO knowledge.articles
              (id, title, topic, content, source_name, source_url, source_updated_at, copyright_notes,
               created_by, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING
            """
                + FIELDS)
        .params(UUID.randomUUID(), input.title(), input.topic(), input.content(), input.sourceName(),
            input.sourceUrl(), DraftFields.timestamp(input.sourceUpdatedAt()), input.copyrightNotes(), actor,
            actor)
        .query(ArticleDraftRepository::map)
        .single();
  }

  @Override
  public List<ArticleDraft> list() {
    return jdbc.sql("SELECT " + FIELDS + " FROM knowledge.articles ORDER BY created_at DESC, id")
        .query(ArticleDraftRepository::map)
        .list();
  }

  @Override
  public Optional<ArticleDraft> get(UUID id) {
    return jdbc.sql("SELECT " + FIELDS + " FROM knowledge.articles WHERE id = ?")
        .param(id)
        .query(ArticleDraftRepository::map)
        .optional();
  }

  /** An edit always goes back to DRAFT and clears an earlier verification and submission. */
  @Override
  public Optional<ArticleDraft> update(UUID id, ArticleDraftInput input, UUID actor, int revision) {
    return jdbc.sql(
            """
            UPDATE knowledge.articles SET
              title = ?, topic = ?, content = ?, source_name = ?, source_url = ?, source_updated_at = ?,
              copyright_notes = ?, updated_by = ?, review_status = 'DRAFT', verified_at = NULL,
              updated_at = now(), revision = revision + 1, submitted_at = NULL, submitted_by = NULL
            WHERE id = ? AND revision = ?"""
                + " AND " + EDITABLE + " RETURNING " + FIELDS)
        .params(input.title(), input.topic(), input.content(), input.sourceName(), input.sourceUrl(),
            DraftFields.timestamp(input.sourceUpdatedAt()), input.copyrightNotes(), actor, id, revision)
        .query(ArticleDraftRepository::map)
        .optional();
  }

  @Override
  public Optional<ArticleDraft> submit(UUID id, UUID actor, int revision) {
    return jdbc.sql(
            """
            UPDATE knowledge.articles SET
              review_status = 'PENDING_REVIEW', verified_at = NULL, submitted_at = now(), submitted_by = ?,
              updated_by = ?, updated_at = now(), revision = revision + 1
            WHERE id = ? AND revision = ?"""
                + " AND " + EDITABLE + " RETURNING " + FIELDS)
        .params(actor, actor, id, revision)
        .query(ArticleDraftRepository::map)
        .optional();
  }

  @Override
  public boolean delete(UUID id) {
    return jdbc.sql("DELETE FROM knowledge.articles WHERE id = ? AND " + EDITABLE).param(id).update() > 0;
  }

  static ArticleDraft map(ResultSet rs, int row) throws SQLException {
    return new ArticleDraft(
        rs.getString("id"),
        rs.getString("title"),
        rs.getString("topic"),
        rs.getString("content"),
        rs.getString("source_name"),
        rs.getString("source_url"),
        time(rs, "source_updated_at"),
        rs.getString("copyright_notes"),
        time(rs, "verified_at"),
        ReviewStatus.valueOf(rs.getString("review_status")),
        rs.getString("created_by"),
        rs.getString("updated_by"),
        time(rs, "created_at"),
        time(rs, "updated_at"),
        rs.getInt("revision"),
        time(rs, "submitted_at"),
        rs.getString("submitted_by"));
  }

  static String time(ResultSet rs, String column) throws SQLException {
    return Instants.iso(rs.getObject(column, OffsetDateTime.class));
  }
}
