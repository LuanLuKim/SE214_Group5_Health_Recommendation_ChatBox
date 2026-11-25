package knowledge.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import knowledge.JdbcKnowledgeSearch;
import shared.domain.ReviewStatus;
import shared.web.ApiException;
import support.TestDatabase;

/** UC-15 article and template drafts on PostgreSQL (port of the authoring tests of PR #164). */
class DraftServiceTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final UUID EXPERT = UUID.fromString("00000000-0000-4000-8000-0000000000e1");
  static final UUID ADMIN = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
  static final UUID MISSING = UUID.fromString("00000000-0000-4000-8000-999999999999");

  JdbcClient jdbc;
  ArticleDrafts articles;
  TemplateDrafts templates;

  @BeforeEach
  void freshDatabase() {
    jdbc = JdbcClient.create(TestDatabase.fresh());
    for (UUID id : List.of(EXPERT, ADMIN))
      jdbc.sql("INSERT INTO accounts.users (id, email, display_name) VALUES (?, ?, 'Synthetic editor')")
          .params(id, id + "@example.invalid")
          .update();
    articles = new ArticleDrafts(new ArticleDraftRepository(jdbc), JSON);
    templates = new TemplateDrafts(new TemplateDraftRepository(jdbc, JSON), JSON);
  }

  static ObjectNode article() {
    return JSON.valueToTree(Map.of(
        "title", "[TEST] Bài nháp thử nghiệm",
        "topic", "Kiểm thử",
        "content", "Văn bản giả lập, không có hướng dẫn y tế.",
        "sourceName", "Synthetic fixture",
        "sourceUrl", "https://example.invalid/article",
        "sourceUpdatedAt", "2026-10-29",
        "copyrightNotes", "Nội dung tổng hợp để kiểm thử."));
  }

  static ObjectNode template() {
    ObjectNode node = JSON.createObjectNode()
        .put("title", "[TEST] Mẫu lịch sinh hoạt")
        .put("goal", "SLEEP")
        .put("summary", "Mẫu giả lập.")
        .put("durationDays", 7)
        .put("applicability", "Dữ liệu giả lập dành cho kiểm thử.")
        .put("advisory", "Hỏi ý kiến nhân viên y tế trước khi áp dụng mẫu thực tế.")
        .put("sourceName", "Synthetic fixture")
        .put("sourceUrl", "https://example.invalid/template");
    node.putArray("activities").addObject().put("id", "demo").put("title", "Hoạt động giả lập");
    node.putArray("notSuitableFor").add("Người đang điều trị bệnh cấp");
    node.putArray("excludedAllergens").add("đậu phộng");
    return node;
  }

  static ObjectNode with(ObjectNode base, Consumer<ObjectNode> change) {
    ObjectNode copy = base.deepCopy();
    change.accept(copy);
    return copy;
  }

  static JsonNode fields(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static void assertError(Runnable call, int status, String code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status().value()).isEqualTo(status);
          assertThat(e.code()).isEqualTo(code);
        });
  }

  void setState(String table, String id, String status) {
    jdbc.sql("UPDATE " + table + " SET review_status = ? WHERE id = ?").params(status, UUID.fromString(id)).update();
  }

  // -- articles ----------------------------------------------------------------

  @Test
  void articleCrudKeepsContentAndRecordsTheRealEditors() {
    ArticleDraft created = articles.create(with(article(), a -> a.put("title", "  [TEST] Bài nháp thử nghiệm  ")), EXPERT);
    assertThat(created.title()).isEqualTo("[TEST] Bài nháp thử nghiệm");
    assertThat(created.reviewStatus()).isEqualTo(ReviewStatus.DRAFT);
    assertThat(created.verifiedAt()).isNull();
    assertThat(created.sourceUpdatedAt()).isEqualTo("2026-10-29T00:00:00.000Z");
    assertThat(created.createdBy()).isEqualTo(EXPERT.toString());
    assertThat(created.updatedBy()).isEqualTo(EXPERT.toString());
    assertThat(created.revision()).isEqualTo(1);
    assertThat(created.submittedAt()).isNull();
    assertThat(created.createdAt()).isNotNull();
    UUID id = UUID.fromString(created.id());
    assertThat(articles.get(id)).isEqualTo(created);
    assertThat(articles.list()).contains(created);

    ArticleDraft edited = articles.update(id, fields("{\"title\":\"[TEST] Đã sửa\",\"sourceUpdatedAt\":null}"), ADMIN);
    assertThat(edited.title()).isEqualTo("[TEST] Đã sửa");
    assertThat(edited.content()).isEqualTo(created.content());
    assertThat(edited.sourceUpdatedAt()).isNull();
    assertThat(edited.createdBy()).isEqualTo(EXPERT.toString());
    assertThat(edited.updatedBy()).isEqualTo(ADMIN.toString());
    assertThat(edited.revision()).isEqualTo(2);

    articles.delete(id);
    assertError(() -> articles.get(id), 404, "NOT_FOUND");
  }

  @Test
  void invalidArticleInputNeverChangesStorage() {
    ArticleDraft created = articles.create(article(), EXPERT);
    UUID id = UUID.fromString(created.id());
    List<String> invalid = List.of(
        "{\"title\":\"\"}", "{\"topic\":\"   \"}", "{\"content\":\"\"}", "{\"sourceName\":\"\"}",
        "{\"sourceName\":null}", "{\"sourceUrl\":\"\"}", "{\"sourceUrl\":\"not-a-url\"}",
        "{\"sourceUrl\":\"ftp://example.invalid/source\"}",
        "{\"sourceUrl\":\"https://user:secret@example.invalid/source\"}",
        "{\"sourceUpdatedAt\":\"2026-02-30\"}", "{\"sourceUpdatedAt\":\"yesterday\"}",
        "{\"title\":\"" + "x".repeat(301) + "\"}", "{\"sourceName\":\"" + "x".repeat(201) + "\"}",
        "{\"reviewStatus\":\"APPROVED\"}", "{\"verifiedAt\":\"2026-10-29T00:00:00Z\"}",
        "{\"createdBy\":\"" + ADMIN + "\"}", "{\"revision\":99}", "{\"isSynthetic\":true}");
    long before = jdbc.sql("SELECT count(*) FROM knowledge.articles").query(Long.class).single();
    for (String json : invalid) {
      ObjectNode body = article().deepCopy();
      body.setAll((ObjectNode) fields(json));
      assertError(() -> articles.create(body, EXPERT), 400, "INVALID_INPUT");
      assertError(() -> articles.update(id, fields(json), EXPERT), 400, "INVALID_INPUT");
    }
    ObjectNode missingSource = article();
    missingSource.remove("sourceUrl");
    assertError(() -> articles.create(missingSource, EXPERT), 400, "INVALID_INPUT");
    assertError(() -> articles.update(id, fields("{}"), EXPERT), 400, "INVALID_INPUT");
    assertError(() -> articles.create(fields("[]"), EXPERT), 400, "INVALID_INPUT");
    assertThat(jdbc.sql("SELECT count(*) FROM knowledge.articles").query(Long.class).single()).isEqualTo(before);
    assertThat(articles.get(id)).isEqualTo(created);
  }

  @Test
  void unknownIdsAre404() {
    assertError(() -> articles.get(MISSING), 404, "NOT_FOUND");
    assertError(() -> articles.update(MISSING, fields("{\"title\":\"Changed\"}"), EXPERT), 404, "NOT_FOUND");
    assertError(() -> articles.delete(MISSING), 404, "NOT_FOUND");
    assertError(() -> articles.submit(MISSING, null, EXPERT), 404, "NOT_FOUND");
    assertError(() -> templates.get(MISSING), 404, "NOT_FOUND");
    assertError(() -> templates.delete(MISSING), 404, "NOT_FOUND");
  }

  @Test
  void publicSearchOnlyShowsApprovedVerifiedArticles() {
    List<String> states = List.of("DRAFT", "PENDING_REVIEW", "RETURNED", "RETIRED", "APPROVED", "APPROVED");
    String visible = null;
    for (int i = 0; i < states.size(); i++) {
      int n = i;
      ArticleDraft created = articles.create(with(article(), a -> a.put("title", "[TEST] Visibility " + n)), EXPERT);
      // editorial states are simulated: publishing is a UC-17 decision, not an authoring action
      jdbc.sql("UPDATE knowledge.articles SET review_status = ?, verified_at = CASE WHEN ? THEN NULL ELSE now() END WHERE id = ?")
          .params(states.get(i), i == 4, UUID.fromString(created.id()))
          .update();
      if (i == 5) visible = created.id();
    }
    JdbcKnowledgeSearch search = new JdbcKnowledgeSearch(jdbc);
    assertThat(search.articles("Visibility").stream().map(a -> a.id())).containsExactly(visible);
    assertThat(articles.list()).hasSizeGreaterThanOrEqualTo(6);
  }

  @Test
  void crawlerRowsStayReadableAndUnverified() {
    UUID id = UUID.fromString("00000000-0000-4000-8000-000000000080");
    // the columns M7's db_row writes; the new ones keep their defaults
    jdbc.sql("""
            INSERT INTO knowledge.articles (id, title, topic, content, source_name, source_url, review_status, is_synthetic)
            VALUES (?, 'Bài mẫu chuẩn hóa', 'general', 'Giới thiệu', 'SYNTHETIC', 'https://example.com/synthetic-article', 'DRAFT', false)""")
        .param(id)
        .update();
    ArticleDraft imported = articles.get(id);
    assertThat(imported.sourceName()).isEqualTo("SYNTHETIC");
    assertThat(imported.sourceUpdatedAt()).isNull();
    assertThat(imported.verifiedAt()).isNull();
    assertThat(imported.createdBy()).isNull();
    assertThat(imported.revision()).isEqualTo(1);
    // a db_row is not an API body: snake_case fields are refused
    assertError(() -> articles.create(fields("{\"title\":\"x\",\"topic\":\"y\",\"content\":\"z\",\"source_name\":\"n\",\"source_url\":\"https://example.com\"}"), EXPERT),
        400, "INVALID_INPUT");
  }

  // -- templates ---------------------------------------------------------------

  @Test
  void templateCrudKeepsActivitiesExclusionsAndSource() {
    TemplateDraft created = templates.create(with(template(), t -> t.put("sourceUpdatedAt", "2026-10-29")), EXPERT);
    assertThat(created.reviewStatus()).isEqualTo(ReviewStatus.DRAFT);
    assertThat(created.verifiedAt()).isNull();
    assertThat(created.activities()).containsExactly(new TemplateDraftInput.Activity("demo", "Hoạt động giả lập", null));
    assertThat(created.sourceUpdatedAt()).isEqualTo("2026-10-29T00:00:00.000Z");
    assertThat(created.createdBy()).isEqualTo(EXPERT.toString());
    UUID id = UUID.fromString(created.id());
    assertThat(templates.get(id)).isEqualTo(created);
    assertThat(templates.list()).contains(created);

    TemplateDraft updated = templates.update(id, fields("{\"title\":\"[TEST] Đã sửa mẫu\"}"), ADMIN);
    assertThat(updated.excludedAllergens()).containsExactly("đậu phộng");
    assertThat(updated.notSuitableFor()).containsExactly("Người đang điều trị bệnh cấp");
    assertThat(updated.activities()).isEqualTo(created.activities());
    assertThat(updated.durationDays()).isEqualTo(7);
    assertThat(updated.advisory()).isEqualTo(created.advisory());
    assertThat(updated.updatedBy()).isEqualTo(ADMIN.toString());
    assertThat(updated.revision()).isEqualTo(2);
    // the stored activities are what M6 reads
    assertThat(fields(jdbc.sql("SELECT activities::text FROM lifestyle.templates WHERE id = ?").param(id).query(String.class).single()))
        .isEqualTo(fields("[{\"id\":\"demo\",\"title\":\"Hoạt động giả lập\"}]"));

    templates.delete(id);
    assertError(() -> templates.get(id), 404, "NOT_FOUND");
  }

  @Test
  void invalidTemplateInputNeverChangesStorage() {
    TemplateDraft created = templates.create(template(), EXPERT);
    UUID id = UUID.fromString(created.id());
    List<String> invalid = List.of(
        "{\"goal\":\"UNKNOWN\"}", "{\"activities\":[]}",
        "{\"activities\":[{\"id\":\"demo\",\"title\":\"A\"},{\"id\":\"demo\",\"title\":\"B\"}]}",
        "{\"activities\":[{\"id\":\"\",\"title\":\"Invalid\"}]}", "{\"activities\":[{\"id\":\"demo\",\"title\":\"\"}]}",
        "{\"activities\":[{\"id\":\"demo\",\"title\":\"A\",\"time\":\"07:00\"}]}",
        "{\"durationDays\":0}", "{\"durationDays\":366}", "{\"durationDays\":1.5}", "{\"applicability\":\"\"}",
        "{\"advisory\":\"  \"}", "{\"excludedAllergens\":[\"\"]}", "{\"sourceName\":\"\"}", "{\"sourceUrl\":\"\"}",
        "{\"sourceUrl\":\"file:///private\"}", "{\"sourceUrl\":\"https://user:secret@example.invalid/template\"}",
        "{\"reviewStatus\":\"APPROVED\"}", "{\"verifiedAt\":\"2026-10-29T00:00:00Z\"}",
        "{\"submittedBy\":\"" + ADMIN + "\"}", "{\"revision\":50}");
    long before = jdbc.sql("SELECT count(*) FROM lifestyle.templates").query(Long.class).single();
    for (String json : invalid) {
      ObjectNode body = template().deepCopy();
      body.setAll((ObjectNode) fields(json));
      assertError(() -> templates.create(body, EXPERT), 400, "INVALID_INPUT");
      assertError(() -> templates.update(id, fields(json), EXPERT), 400, "INVALID_INPUT");
    }
    assertError(() -> templates.update(id, fields("{}"), EXPERT), 400, "INVALID_INPUT");
    assertThat(jdbc.sql("SELECT count(*) FROM lifestyle.templates").query(Long.class).single()).isEqualTo(before);
    assertThat(templates.get(id)).isEqualTo(created);
  }

  @Test
  void templateAPlanUsesCannotBeDeleted() {
    TemplateDraft created = templates.create(template(), EXPERT);
    UUID id = UUID.fromString(created.id());
    jdbc.sql("INSERT INTO lifestyle.plans (id, user_id, template_id, started_on) VALUES (?, ?, ?, current_date)")
        .params(UUID.randomUUID(), EXPERT, id)
        .update();
    assertError(() -> templates.delete(id), 409, "TEMPLATE_IN_USE");
    assertThat(templates.get(id)).isEqualTo(created);
  }

  @Test
  void approvedTemplateIsWhatM6Reads() {
    TemplateDraft created = templates.create(template(), EXPERT);
    setState("lifestyle.templates", created.id(), "APPROVED");
    // M6 (UC-12) lists APPROVED templates: the authored fields are in the columns it reads
    Map<String, Object> row = jdbc.sql(
            "SELECT title, goal, duration_days, summary, requires_profile FROM lifestyle.templates WHERE id = ? AND review_status = 'APPROVED'")
        .param(UUID.fromString(created.id()))
        .query()
        .singleRow();
    assertThat(row).containsEntry("goal", "SLEEP").containsEntry("duration_days", 7).containsEntry("requires_profile", false);
  }

  @Test
  void legacyTemplateMustBeCompletedBeforeEditOrSubmit() {
    UUID id = UUID.fromString("00000000-0000-4000-8000-000000000083");
    jdbc.sql("""
            INSERT INTO lifestyle.templates (id, title, goal, activities, source_url)
            VALUES (?, '[TEST] Legacy', 'SLEEP', '[{"id":"demo","title":"Synthetic"}]', 'https://example.invalid/template')""")
        .param(id)
        .update();
    TemplateDraft legacy = templates.get(id);
    assertThat(legacy.durationDays()).isNull();
    assertThat(legacy.sourceName()).isEmpty();
    assertError(() -> templates.submit(id, null, EXPERT), 400, "INVALID_INPUT");
    assertError(() -> templates.update(id, fields("{\"title\":\"Partial\"}"), EXPERT), 400, "INVALID_INPUT");
    TemplateDraft completed = templates.update(id, template(), EXPERT);
    assertThat(completed.verifiedAt()).isNull();
    assertThat(templates.submit(id, null, EXPERT).reviewStatus()).isEqualTo(ReviewStatus.PENDING_REVIEW);
  }

  // -- submission (both kinds) ---------------------------------------------------

  @Test
  void draftsAndReturnedContentCanBeSubmittedButNeverSelfApproved() {
    for (UUID actor : List.of(EXPERT, ADMIN)) {
      checkSubmission(articles, article(), "knowledge.articles", actor);
      checkSubmission(templates, template(), "lifestyle.templates", actor);
    }
  }

  <I extends DraftInput<I>, D extends Draft<I>> void checkSubmission(DraftService<I, D> drafts, ObjectNode body,
      String table, UUID actor) {
    D item = drafts.create(body, actor);
    UUID id = UUID.fromString(item.id());
    D submitted = drafts.submit(id, fields("{}"), actor);
    assertThat(submitted.reviewStatus()).isEqualTo(ReviewStatus.PENDING_REVIEW);
    assertThat(submitted.revision()).isEqualTo(item.revision() + 1);
    assertThat(field(submitted, "submittedBy")).isEqualTo(actor.toString());
    assertThat(field(submitted, "submittedAt")).isNotNull();
    assertThat(field(submitted, "verifiedAt")).isNull();

    jdbc.sql("UPDATE " + table + " SET review_status = 'RETURNED', verified_at = now() WHERE id = ?").param(id).update();
    D again = drafts.submit(id, null, actor);
    assertThat(again.reviewStatus()).isEqualTo(ReviewStatus.PENDING_REVIEW);
    assertThat(field(again, "verifiedAt")).isNull();

    setState(table, item.id(), "RETURNED");
    D fixed = drafts.update(id, fields("{\"title\":\"[TEST] Fixed\"}"), actor);
    assertThat(fixed.reviewStatus()).isEqualTo(ReviewStatus.DRAFT);
    assertThat(field(fixed, "submittedBy")).isNull();
    assertThat(field(fixed, "submittedAt")).isNull();
    assertThat(drafts.submit(id, null, actor).revision()).isEqualTo(fixed.revision() + 1);

    D other = drafts.create(body, actor);
    UUID otherId = UUID.fromString(other.id());
    assertError(() -> drafts.submit(otherId, fields("{\"reviewStatus\":\"APPROVED\"}"), actor), 400, "INVALID_INPUT");
    assertThat(drafts.get(otherId).reviewStatus()).isEqualTo(ReviewStatus.DRAFT);
  }

  @Test
  void pendingApprovedAndRetiredContentIsLocked() {
    checkLocked(articles, article(), "knowledge.articles");
    checkLocked(templates, template(), "lifestyle.templates");
  }

  <I extends DraftInput<I>, D extends Draft<I>> void checkLocked(DraftService<I, D> drafts, ObjectNode body, String table) {
    for (String status : List.of("PENDING_REVIEW", "APPROVED", "RETIRED")) {
      D item = drafts.create(body, EXPERT);
      UUID id = UUID.fromString(item.id());
      setState(table, item.id(), status);
      D before = drafts.get(id);
      assertError(() -> drafts.update(id, fields("{\"title\":\"Blocked\"}"), EXPERT), 409, "CONTENT_NOT_EDITABLE");
      assertError(() -> drafts.delete(id), 409, "CONTENT_NOT_EDITABLE");
      assertError(() -> drafts.submit(id, null, EXPERT), 409, "CONTENT_NOT_SUBMITTABLE");
      assertThat(drafts.get(id)).isEqualTo(before);
    }
  }

  @Test
  void submissionChecksTheStoredSourceAndRefusesAConcurrentEdit() {
    ArticleDraft invalid = articles.create(article(), EXPERT);
    jdbc.sql("UPDATE knowledge.articles SET source_url = 'ftp://example.invalid/source' WHERE id = ?")
        .param(UUID.fromString(invalid.id()))
        .update();
    assertError(() -> articles.submit(UUID.fromString(invalid.id()), null, EXPERT), 400, "INVALID_INPUT");
    assertThat(articles.get(UUID.fromString(invalid.id())).reviewStatus()).isEqualTo(ReviewStatus.DRAFT);

    ArticleDraft item = articles.create(article(), EXPERT);
    UUID id = UUID.fromString(item.id());
    // an edit lands between the check of the submission and its write
    ArticleDraftRepository racing = new ArticleDraftRepository(jdbc) {
      boolean edited;

      @Override
      public Optional<ArticleDraft> get(UUID draftId) {
        Optional<ArticleDraft> read = super.get(draftId);
        if (!edited) {
          edited = true;
          articles.update(draftId, fields("{\"title\":\"[TEST] Concurrent edit\"}"), ADMIN);
        }
        return read;
      }
    };
    ArticleDrafts racingDrafts = new ArticleDrafts(racing, JSON);
    assertError(() -> racingDrafts.submit(id, null, EXPERT), 409, "CONTENT_CHANGED");
    ArticleDraft after = articles.get(id);
    assertThat(after.title()).isEqualTo("[TEST] Concurrent edit");
    assertThat(after.reviewStatus()).isEqualTo(ReviewStatus.DRAFT);
    assertThat(after.submittedAt()).isNull();
    assertThat(after.revision()).isEqualTo(item.revision() + 1);
  }

  static Object field(Object draft, String name) {
    return JSON.valueToTree(draft).get(name) == null || JSON.valueToTree(draft).get(name).isNull()
        ? null
        : JSON.valueToTree(draft).get(name).asText();
  }
}
