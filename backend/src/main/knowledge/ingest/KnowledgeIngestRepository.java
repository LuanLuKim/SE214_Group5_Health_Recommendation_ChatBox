package knowledge.ingest;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import knowledge.ingest.IngestSource.ArticleRow;
import knowledge.ingest.IngestSource.ChunkRow;
import knowledge.ingest.IngestSource.ReviewDecision;

/**
 * SQL of the loader. Values are always bound. Rows land as DRAFT with rag_eligible = false; a row whose
 * text did not change keeps the review decision it has, and a row whose text changed goes back to DRAFT
 * (the old approval does not cover the new text). Articles written by a person (UC-15) are never touched.
 */
class KnowledgeIngestRepository {
  enum Outcome { INSERTED, UPDATED, UNCHANGED }

  private final JdbcClient jdbc;

  KnowledgeIngestRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Outcome upsertArticle(ArticleRow a) {
    return outcome(jdbc.sql("""
            INSERT INTO knowledge.articles AS t (id, title, topic, content, source_name, source_url, source_updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
              title = EXCLUDED.title, topic = EXCLUDED.topic, content = EXCLUDED.content,
              source_name = EXCLUDED.source_name, source_url = EXCLUDED.source_url,
              source_updated_at = EXCLUDED.source_updated_at, updated_at = now(),
              revision = t.revision + 1,
              review_status = CASE WHEN (t.title, t.content) = (EXCLUDED.title, EXCLUDED.content)
                                   THEN t.review_status ELSE 'DRAFT' END,
              verified_at = CASE WHEN (t.title, t.content) = (EXCLUDED.title, EXCLUDED.content)
                                 THEN t.verified_at END,
              submitted_at = CASE WHEN (t.title, t.content) = (EXCLUDED.title, EXCLUDED.content)
                                  THEN t.submitted_at END,
              submitted_by = CASE WHEN (t.title, t.content) = (EXCLUDED.title, EXCLUDED.content)
                                  THEN t.submitted_by END
            WHERE t.created_by IS NULL AND NOT t.is_synthetic
              AND (t.title, t.topic, t.content, t.source_name, t.source_url, t.source_updated_at)
                  IS DISTINCT FROM (EXCLUDED.title, EXCLUDED.topic, EXCLUDED.content, EXCLUDED.source_name,
                                    EXCLUDED.source_url, EXCLUDED.source_updated_at)
            RETURNING (xmax = 0) AS inserted
            """)
        .params(a.id(), a.title(), a.topic(), a.content(), a.sourceName(), a.sourceUrl(), a.sourceUpdatedAt())
        .query(Boolean.class)
        .optional()
        .orElse(null));
  }

  Outcome upsertChunk(ChunkRow c) {
    return outcome(jdbc.sql("""
            INSERT INTO knowledge.chunks AS t (id, article_id, chunk_key, heading, heading_path, text, original_text,
              language, word_count, text_sha256, scope_tags, rule_tags, source_url, fetched_at,
              normalizer_version, translation_model, translation_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
              article_id = EXCLUDED.article_id, chunk_key = EXCLUDED.chunk_key, heading = EXCLUDED.heading,
              heading_path = EXCLUDED.heading_path, text = EXCLUDED.text, original_text = EXCLUDED.original_text,
              language = EXCLUDED.language, word_count = EXCLUDED.word_count, text_sha256 = EXCLUDED.text_sha256,
              scope_tags = EXCLUDED.scope_tags, rule_tags = EXCLUDED.rule_tags, source_url = EXCLUDED.source_url,
              fetched_at = EXCLUDED.fetched_at, normalizer_version = EXCLUDED.normalizer_version,
              translation_model = EXCLUDED.translation_model, translation_status = EXCLUDED.translation_status,
              review_status = CASE WHEN t.text_sha256 = EXCLUDED.text_sha256 AND t.review_status <> 'RETIRED'
                                   THEN t.review_status ELSE 'DRAFT' END,
              rag_eligible = CASE WHEN t.text_sha256 = EXCLUDED.text_sha256 AND t.review_status <> 'RETIRED'
                                  THEN t.rag_eligible ELSE false END
            WHERE t.review_status = 'RETIRED'
              OR (t.article_id, t.chunk_key, t.heading, t.heading_path, t.text_sha256, t.original_text, t.language,
                  t.word_count, t.scope_tags, t.rule_tags, t.source_url, t.fetched_at, t.normalizer_version,
                  t.translation_model, t.translation_status)
                 IS DISTINCT FROM
                 (EXCLUDED.article_id, EXCLUDED.chunk_key, EXCLUDED.heading, EXCLUDED.heading_path,
                  EXCLUDED.text_sha256, EXCLUDED.original_text, EXCLUDED.language, EXCLUDED.word_count,
                  EXCLUDED.scope_tags, EXCLUDED.rule_tags, EXCLUDED.source_url, EXCLUDED.fetched_at,
                  EXCLUDED.normalizer_version, EXCLUDED.translation_model, EXCLUDED.translation_status)
            RETURNING (xmax = 0) AS inserted
            """)
        .params(
            c.id(), c.articleId(), c.chunkKey(), c.heading(), c.headingPath().toArray(String[]::new), c.text(),
            c.originalText(), c.language(), c.wordCount(), c.textSha256(), c.scopeTags().toArray(String[]::new),
            c.ruleTags().toArray(String[]::new), c.sourceUrl(), c.fetchedAt(), c.normalizerVersion(),
            c.translationModel(), c.translationStatus() == null ? null : c.translationStatus().value())
        .query(Boolean.class)
        .optional()
        .orElse(null));
  }

  /**
   * Chunks of the loaded articles that are no longer in the files (the source text changed, so the
   * chunk got a new id) are retired: kept for the review history, never served.
   */
  int retireMissing(Collection<UUID> articles, Collection<UUID> chunks) {
    return jdbc.sql("""
            UPDATE knowledge.chunks SET review_status = 'RETIRED', rag_eligible = false
            WHERE article_id = ANY(?) AND NOT (id = ANY(?)) AND review_status <> 'RETIRED'
            """)
        .params(articles.toArray(UUID[]::new), chunks.toArray(UUID[]::new))
        .update();
  }

  /** The account decisions are recorded under must be an active CONTENT_EXPERT (a real reviewer, not admin). */
  boolean isActiveContentExpert(UUID user) {
    return jdbc.sql("""
            SELECT count(*) FROM accounts.users u JOIN accounts.user_roles r ON r.user_id = u.id
            WHERE u.id = ? AND u.status = 'ACTIVE' AND r.role = 'CONTENT_EXPERT'
            """)
        .param(user)
        .query(Integer.class)
        .single() > 0;
  }

  /**
   * One SCRUM-148 decision as a CHUNK review event, at the time it was recorded. The event id is derived
   * from the decision, so loading the same file again adds nothing. Returns 1 when a row was added.
   */
  int recordDecision(ReviewDecision d, UUID reviewer) {
    UUID id = UUID.nameUUIDFromBytes(("scrum148:" + d.chunkId() + ":" + d.decision() + ":" + d.recordedAt())
        .getBytes(StandardCharsets.UTF_8));
    return jdbc.sql("""
            INSERT INTO knowledge.review_events (id, subject_type, subject_id, reviewer_id, decision, note, created_at)
            VALUES (?, 'CHUNK', ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """)
        .params(id, d.chunkId(), reviewer, d.decision(), d.note() == null ? "" : d.note(), d.recordedAt())
        .update();
  }

  private static Outcome outcome(Boolean inserted) {
    if (inserted == null) return Outcome.UNCHANGED;
    return inserted ? Outcome.INSERTED : Outcome.UPDATED;
  }
}
