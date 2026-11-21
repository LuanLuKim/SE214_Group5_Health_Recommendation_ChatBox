package vn.edu.uit.se214.health.knowledge.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.uit.se214.health.knowledge.ingest.KnowledgeIngestRepository.Outcome;
import vn.edu.uit.se214.health.support.TestDatabase;

/**
 * SCRUM-48 loader on the real migrations plus the proposed knowledge.chunks table, with small [TEST]
 * files shaped like the M7 output (normalizer + tools/translate).
 */
class KnowledgeIngestTest {
  static final UUID ARTICLE = UUID.fromString("00000000-0000-4000-8000-0000000007a1");
  static final UUID REFERENCE = UUID.fromString("00000000-0000-4000-8000-0000000007a2");
  static final UUID VERIFIED = UUID.fromString("00000000-0000-4000-8000-0000000007c1");
  static final UUID FLAGGED = UUID.fromString("00000000-0000-4000-8000-0000000007c2");
  static final UUID CORRECTED = UUID.fromString("00000000-0000-4000-8000-0000000007c3");
  static final UUID NATIVE = UUID.fromString("00000000-0000-4000-8000-0000000007c4");
  static final UUID REFERENCE_CHUNK = UUID.fromString("00000000-0000-4000-8000-0000000007c5");
  static final String FETCHED = "2026-11-03T08:17:52.274596+00:00";
  static final String FETCHED_NATIVE = "2026-11-10T11:58:13+00:00";

  final ObjectMapper json = new ObjectMapper();
  @TempDir Path dir;
  JdbcClient jdbc;
  KnowledgeIngest ingest;
  Path normalized;
  Path translated;
  List<ObjectNode> chunks;
  List<ObjectNode> articles;

  @BeforeEach
  void setUp() throws IOException {
    DataSource dataSource = TestDatabase.fresh();
    var schema = new ResourceDatabasePopulator(new ClassPathResource("knowledge-ingest/proposed_knowledge_chunks.sql"));
    schema.setSqlScriptEncoding("UTF-8");
    schema.execute(dataSource);
    jdbc = JdbcClient.create(dataSource);
    ingest = new KnowledgeIngest(new IngestSource(json), new KnowledgeIngestRepository(jdbc),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    normalized = Files.createDirectories(dir.resolve("normalized/articles")).getParent();
    translated = Files.createDirectories(dir.resolve("translated"));
    articles = new ArrayList<>(List.of(
        article(ARTICLE, "rag", "[TEST] Viêm da tiếp xúc", "[TEST] Nội dung bài."),
        article(REFERENCE, "expert_reference", "[TEST] Trang thuốc", "[TEST] Chỉ cho chuyên gia.")));
    chunks = new ArrayList<>(List.of(
        chunk(VERIFIED, ARTICLE, "rag", "[TEST] Bôi kem dưỡng ẩm.", "[TEST] Use an emollient.",
            meta("auto_verified", false, 0)),
        chunk(FLAGGED, ARTICLE, "rag", "[TEST] Gọi 115 nếu khó thở.", "[TEST] Call 999 if breathless.",
            meta("auto_verified_with_warnings", true, 0)),
        chunk(CORRECTED, ARTICLE, "rag", "[TEST] Trẻ dưới 1 tuổi.", "[TEST] Babies.", meta("auto_verified", false, 2)),
        chunk(NATIVE, ARTICLE, "rag", "[TEST] Hỏi đáp sốt xuất huyết.", "", meta("passthrough_native", false, 0)),
        chunk(REFERENCE_CHUNK, REFERENCE, "expert_reference", "[TEST] Liều thuốc.", "[TEST] Dose.",
            meta("auto_verified", false, 0))));
    chunks.get(3).put("language", "vi");
  }

  @Test
  void everyRowLandsAsDraftWithItsRealCrawlTimeAndTheReferenceCorpusStaysOut() throws IOException {
    KnowledgeIngest.Report report = run();

    assertThat(report.articles()).containsEntry(Outcome.INSERTED, 1);
    assertThat(report.chunks()).containsEntry(Outcome.INSERTED, 4);
    assertThat(report.skippedOutsideRag()).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM knowledge.articles WHERE id = ?", REFERENCE)).isZero();
    assertThat(count("SELECT count(*) FROM knowledge.chunks WHERE id = ?", REFERENCE_CHUNK)).isZero();
    assertThat(count("""
        SELECT count(*) FROM knowledge.articles
        WHERE id = ? AND review_status = 'DRAFT' AND verified_at IS NULL AND created_by IS NULL
          AND source_updated_at IS NOT NULL
        """, ARTICLE)).isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM knowledge.chunks
        WHERE review_status = 'DRAFT' AND NOT rag_eligible AND source_url <> '' AND fetched_at IS NOT NULL
        """)).isEqualTo(4);
    assertThat(jdbc.sql("SELECT fetched_at FROM knowledge.chunks WHERE id = ?").param(NATIVE)
        .query(OffsetDateTime.class).single()).isEqualTo(OffsetDateTime.parse(FETCHED_NATIVE));
    assertThat(jdbc.sql("SELECT fetched_at FROM knowledge.chunks WHERE id = ?").param(VERIFIED)
        .query(OffsetDateTime.class).single()).isEqualTo(OffsetDateTime.parse(FETCHED));
  }

  @Test
  void theTranslationGateResultIsKeptPerChunkSoApprovingAnArticleOnlyTurnsOnChunksThatPassed() throws IOException {
    run();

    assertThat(status(VERIFIED)).isEqualTo("auto_verified");
    assertThat(status(FLAGGED)).isEqualTo("flagged");
    // corrected lines, but no expert checked this text again: what the gate says (M2, 20/11)
    assertThat(status(CORRECTED)).isEqualTo("auto_verified");
    assertThat(status(NATIVE)).isEqualTo("none");
    assertThat(count("SELECT count(*) FROM knowledge.chunks WHERE id = ? AND original_text IS NULL", NATIVE))
        .isEqualTo(1);
    assertThat(jdbc.sql("SELECT original_text FROM knowledge.chunks WHERE id = ?").param(VERIFIED)
        .query(String.class).single()).isEqualTo("[TEST] Use an emollient.");
    assertThat(count("SELECT count(*) FROM knowledge.chunks WHERE id = ? AND translation_model IS NULL", NATIVE))
        .isEqualTo(1);
    assertThat(count("""
        SELECT count(*) FROM knowledge.chunks
        WHERE search @@ to_tsquery('simple', 'emollient | dưỡng') AND id = ?
        """, VERIFIED)).isEqualTo(1);
  }

  @Test
  void aChunkWithoutAGateResultIsPending() throws IOException {
    chunks.get(0).set("translation_metadata", json.createObjectNode().put("model", "[TEST]-model"));
    run();

    assertThat(status(VERIFIED)).isEqualTo("pending");
  }

  @Test
  void reviewerCorrectedOnlyWhenTheExpertApprovedExactlyTheTextBeingLoaded() throws IOException {
    Path approvedThis = decisions(decision(CORRECTED, "APPROVED", "[TEST] Trẻ dưới 1 tuổi.", null));
    run(List.of(approvedThis), null);
    assertThat(status(CORRECTED)).isEqualTo("reviewer_corrected");

    Path approvedOlder = decisions(decision(CORRECTED, "APPROVED", "[TEST] Bản cũ đã duyệt.", null));
    run(List.of(approvedOlder), null);
    assertThat(status(CORRECTED)).isEqualTo("auto_verified");

    Path returned = decisions(decision(CORRECTED, "RETURNED", "[TEST] Trẻ dưới 1 tuổi.", null));
    run(List.of(returned), null);
    assertThat(status(CORRECTED)).isEqualTo("auto_verified");
  }

  @Test
  void decisionsAreRecordedOnceUnderTheRealExpertAtTheTimeTheyWereMade() throws IOException {
    UUID expert = user("CONTENT_EXPERT", "ACTIVE");
    Path file = decisions(
        decision(CORRECTED, "APPROVED", "[TEST] Trẻ dưới 1 tuổi.", "2026-11-15T15:37:06Z"),
        decision(VERIFIED, "APPROVED", "[TEST] Bôi kem dưỡng ẩm.", null),
        decision(UUID.randomUUID(), "APPROVED", "[TEST] Chunk không có trong file.", "2026-11-15T15:37:06Z"));

    KnowledgeIngest.Report first = run(List.of(file), expert);
    KnowledgeIngest.Report second = run(List.of(file), expert);

    assertThat(first.recordedDecisions()).isEqualTo(1);
    assertThat(second.recordedDecisions()).isZero();
    assertThat(jdbc.sql("""
        SELECT subject_type || '/' || decision || '/' || (reviewer_id = ?) || '/' || (created_at = ?)
        FROM knowledge.review_events WHERE subject_id = ?
        """).params(expert, OffsetDateTime.parse("2026-11-15T15:37:06Z"), CORRECTED).query(String.class).single())
        .isEqualTo("CHUNK/APPROVED/true/true");
    // a recorded decision never switches a chunk on: only approving the article (UC-17) does
    assertThat(count("SELECT count(*) FROM knowledge.chunks WHERE rag_eligible")).isZero();
  }

  @Test
  void decisionsNeedAnActiveContentExpertAndAreLeftOutWithoutOne() throws IOException {
    Path file = decisions(decision(CORRECTED, "APPROVED", "[TEST] Trẻ dưới 1 tuổi.", "2026-11-15T15:37:06Z"));
    UUID admin = user("ADMIN", "ACTIVE");
    UUID locked = user("CONTENT_EXPERT", "LOCKED");

    for (UUID reviewer : List.of(admin, locked)) {
      assertThatThrownBy(() -> run(List.of(file), reviewer))
          .isInstanceOf(IngestException.class).hasMessageContaining("CONTENT_EXPERT");
      assertThat(count("SELECT count(*) FROM knowledge.chunks")).isZero();
    }

    KnowledgeIngest.Report report = run(List.of(file), null);
    assertThat(report.decisionsNotRecorded()).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM knowledge.review_events")).isZero();
    assertThat(count("SELECT count(*) FROM knowledge.chunks")).isEqualTo(4);
  }

  @Test
  void runningTwiceWithTheSameFilesChangesNothing() throws IOException {
    run();
    KnowledgeIngest.Report second = run();

    assertThat(second.articles()).isEqualTo(Map.of(Outcome.INSERTED, 0, Outcome.UPDATED, 0, Outcome.UNCHANGED, 1));
    assertThat(second.chunks()).isEqualTo(Map.of(Outcome.INSERTED, 0, Outcome.UPDATED, 0, Outcome.UNCHANGED, 4));
    assertThat(second.retiredChunks()).isZero();
    assertThat(count("SELECT count(*) FROM knowledge.chunks")).isEqualTo(4);
    assertThat(jdbc.sql("SELECT revision FROM knowledge.articles WHERE id = ?").param(ARTICLE)
        .query(Integer.class).single()).isEqualTo(1);
  }

  @Test
  void aReviewDecisionSurvivesAReloadUntilTheTextChanges() throws IOException {
    run();
    approve();

    run();
    assertThat(count("SELECT count(*) FROM knowledge.chunks WHERE review_status = 'APPROVED' AND rag_eligible"))
        .isEqualTo(4);

    chunks.get(0).put("text_vi", "[TEST] Bôi kem dưỡng ẩm hai lần mỗi ngày.");
    chunks.get(1).putArray("scope_tags").add("emergency");
    KnowledgeIngest.Report report = run();

    assertThat(report.chunks()).containsEntry(Outcome.UPDATED, 2);
    assertThat(jdbc.sql("SELECT review_status || '/' || rag_eligible FROM knowledge.chunks WHERE id = ?")
        .param(VERIFIED).query(String.class).single()).isEqualTo("DRAFT/false");
    assertThat(jdbc.sql("SELECT review_status || '/' || rag_eligible FROM knowledge.chunks WHERE id = ?")
        .param(FLAGGED).query(String.class).single()).isEqualTo("APPROVED/true");
  }

  @Test
  void anArticleWhoseTextChangedGoesBackToDraftAndLosesItsVerificationDate() throws IOException {
    run();
    approve();

    articles.get(0).put("content", "[TEST] Nội dung bài đã cập nhật.");
    run();

    assertThat(jdbc.sql("""
        SELECT review_status || '/' || (verified_at IS NULL) || '/' || revision FROM knowledge.articles WHERE id = ?
        """).param(ARTICLE).query(String.class).single()).isEqualTo("DRAFT/true/2");
  }

  @Test
  void aChunkNoLongerInTheFilesIsRetiredAndComesBackAsDraftIfItReturns() throws IOException {
    run();
    approve();
    ObjectNode removed = chunks.remove(1);

    KnowledgeIngest.Report report = run();
    assertThat(report.retiredChunks()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT review_status || '/' || rag_eligible FROM knowledge.chunks WHERE id = ?")
        .param(FLAGGED).query(String.class).single()).isEqualTo("RETIRED/false");

    chunks.add(1, removed);
    run();
    assertThat(jdbc.sql("SELECT review_status FROM knowledge.chunks WHERE id = ?").param(FLAGGED)
        .query(String.class).single()).isEqualTo("DRAFT");
  }

  @Test
  void contentNotWrittenByTheCrawlerIsNeverOverwritten() throws IOException {
    jdbc.sql("""
        INSERT INTO knowledge.articles (id, title, topic, content, source_name, source_url, review_status, is_synthetic)
        VALUES (?, '[TEST] Bài tổng hợp', 'skin', '[TEST] Giữ nguyên.', 'Nhóm 5', 'https://example.org', 'APPROVED', true)
        """).param(ARTICLE).update();

    KnowledgeIngest.Report report = run();

    assertThat(report.articles()).containsEntry(Outcome.UNCHANGED, 1);
    assertThat(jdbc.sql("SELECT content FROM knowledge.articles WHERE id = ?").param(ARTICLE)
        .query(String.class).single()).isEqualTo("[TEST] Giữ nguyên.");
  }

  @Test
  void badFilesWriteNothing() throws IOException {
    chunks.get(0).put("article_uuid", UUID.randomUUID().toString());

    assertThatThrownBy(this::run).isInstanceOf(IngestException.class).hasMessageContaining("not loaded");
    assertThat(count("SELECT count(*) FROM knowledge.articles WHERE NOT is_synthetic")).isZero();
    assertThat(count("SELECT count(*) FROM knowledge.chunks")).isZero();

    chunks.get(0).put("article_uuid", ARTICLE.toString());
    chunks.get(1).remove("text_vi");
    assertThatThrownBy(this::run).isInstanceOf(IngestException.class).hasMessageContaining("text_vi");
    assertThat(count("SELECT count(*) FROM knowledge.chunks")).isZero();
  }

  @Test
  void theCommandRefusesMissingFolders() {
    assertThatThrownBy(() -> ingest.run(null, translated))
        .isInstanceOf(IngestException.class).hasMessageContaining("normalized-dir");
    assertThatThrownBy(() -> ingest.run(normalized, dir.resolve("missing")))
        .isInstanceOf(IngestException.class).hasMessageContaining("translated-dir");
  }

  // --- [TEST] files shaped like the M7 output -------------------------------------------------------

  KnowledgeIngest.Report run() throws IOException {
    return run(List.of(), null);
  }

  KnowledgeIngest.Report run(List<Path> decisionFiles, UUID reviewer) throws IOException {
    write(translated.resolve("db_rows_vi.json"), json.writeValueAsString(articles));
    StringBuilder vi = new StringBuilder();
    StringBuilder en = new StringBuilder();
    for (ObjectNode c : chunks) {
      vi.append(json.writeValueAsString(c)).append('\n');
      ObjectNode n = json.createObjectNode();
      n.put("id", c.get("chunk_id").asText());
      n.put("fetched_at", c.get("chunk_id").asText().equals(NATIVE.toString()) ? FETCHED_NATIVE : FETCHED);
      n.putArray("rule_tags").add("medication");
      n.put("normalizer_version", "normalize-v2");
      en.append(json.writeValueAsString(n)).append('\n');
    }
    write(translated.resolve("chunks_vi.jsonl"), vi.toString());
    write(normalized.resolve("chunks.jsonl"), en.toString());
    for (ObjectNode a : articles) {
      ObjectNode file = json.createObjectNode();
      file.put("uuid", a.get("id").asText());
      file.put("source_updated_at", "2026-07-09T15:27:58+00:00");
      write(normalized.resolve("articles/" + a.get("id").asText() + ".json"), json.writeValueAsString(file));
    }
    return ingest.run(normalized, translated, decisionFiles, reviewer);
  }

  /** A review_decisions.json shaped like docs/reviews/scrum148: the hash is of the given Vietnamese text. */
  Path decisions(ObjectNode... rows) throws IOException {
    ObjectNode file = json.createObjectNode();
    ArrayNode list = file.putArray("decisions");
    for (ObjectNode row : rows) list.add(row);
    Path path = Files.createTempFile(dir, "review_decisions", ".json");
    write(path, json.writeValueAsString(file));
    return path;
  }

  ObjectNode decision(UUID chunk, String decision, String approvedText, String recordedAt) {
    ObjectNode d = json.createObjectNode();
    d.put("chunk_id", chunk.toString());
    d.put("decision", decision);
    d.put("notes", "[TEST] Đã đọc và xác nhận bản đã sửa.");
    d.put("corrected_text_vi_sha256", IngestSource.sha256(approvedText));
    if (recordedAt != null) d.put("approval_recorded_at", recordedAt);
    return d;
  }

  UUID user(String role, String status) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO accounts.users (id, email, display_name, status) VALUES (?, ?, '[TEST] Reviewer', ?)")
        .params(id, id + "@example.test", status).update();
    jdbc.sql("INSERT INTO accounts.user_roles (user_id, role) VALUES (?, ?)").params(id, role).update();
    return id;
  }

  void approve() {
    jdbc.sql("UPDATE knowledge.articles SET review_status = 'APPROVED', verified_at = now() WHERE id = ?")
        .param(ARTICLE).update();
    jdbc.sql("UPDATE knowledge.chunks SET review_status = 'APPROVED', rag_eligible = true").update();
  }

  ObjectNode article(UUID id, String corpus, String title, String content) {
    ObjectNode a = json.createObjectNode();
    a.put("id", id.toString());
    a.put("title", title);
    a.put("topic", "skin");
    a.put("content", content);
    a.put("source_name", "NHS");
    a.put("source_url", "https://www.nhs.uk/conditions/test/");
    a.putNull("verified_at");
    a.put("review_status", "DRAFT");
    a.put("corpus", corpus);
    a.put("language", "vi");
    return a;
  }

  ObjectNode chunk(UUID id, UUID article, String corpus, String vi, String en, ObjectNode meta) {
    ObjectNode c = json.createObjectNode();
    c.put("chunk_id", id.toString());
    c.put("chunk_key", "test-" + id);
    c.put("article_uuid", article.toString());
    c.put("language", "vi");
    c.put("heading_vi", "[TEST] Mục");
    ArrayNode path = c.putArray("heading_path_vi");
    path.add("[TEST] Điều trị");
    c.put("text_vi", vi);
    c.put("text_en", en);
    c.put("word_count_vi", vi.split("\\s+").length);
    c.putArray("scope_tags").add("medication");
    c.put("corpus", corpus);
    c.put("source_url", "https://www.nhs.uk/conditions/test/");
    c.set("translation_metadata", meta);
    return c;
  }

  ObjectNode meta(String quality, boolean needsReview, int corrected) {
    ObjectNode m = json.createObjectNode();
    m.put("quality_status", quality);
    m.put("needs_human_review", needsReview);
    m.put("human_corrected_lines", corrected);
    m.put("model", quality.equals(TranslationStatus.NATIVE) ? "passthrough" : "[TEST]-model");
    return m;
  }

  String status(UUID chunk) {
    return jdbc.sql("SELECT coalesce(translation_status, 'none') FROM knowledge.chunks WHERE id = ?").param(chunk)
        .query(String.class).single();
  }

  int count(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query(Integer.class).single();
  }

  static void write(Path file, String content) throws IOException {
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }
}
