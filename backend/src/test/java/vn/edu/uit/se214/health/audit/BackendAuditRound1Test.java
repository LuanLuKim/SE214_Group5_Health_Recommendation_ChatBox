package vn.edu.uit.se214.health.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.uit.se214.health.accounts.auth.AccessTokenService;
import vn.edu.uit.se214.health.support.TestDatabase;

/**
 * Backend audit round 1 (docs/reviews/backend-testing-2026-11-16.md): the cases outside M6, run in
 * DATA_MODE=postgres against the synthetic seed. Case IDs are in each display name. See README.md
 * in this package.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
class BackendAuditRound1Test {
  /** Seeded synthetic user who owns session …040 with question …041 and answer …042. */
  private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000001");

  private static final String SESSION = "00000000-0000-4000-8000-000000000040";
  private static final String USER_MESSAGE = "00000000-0000-4000-8000-000000000041";
  private static final String ASSISTANT_MESSAGE = "00000000-0000-4000-8000-000000000042";
  private static final String SEEDED_QUESTION = "Tin nhắn giả lập kiểm tra lịch sử.";
  private static final String SEEDED_ANSWER = "Phản hồi giả lập, không phải tư vấn sức khỏe.";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired AccessTokenService tokens;

  private Cookie adminAccess;
  private String csrf;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    try (Connection c = TestDatabase.server().getPostgresDatabase().getConnection();
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE backend_audit_round1");
    }
    registry.add("spring.datasource.url", () -> TestDatabase.jdbcUrl("backend_audit_round1"));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
    registry.add("app.data-mode", () -> "postgres");
    registry.add("app.seed-synthetic", () -> "true");
    // 06-01 needs the AI service to be unconfigured.
    registry.add("app.ai-service.base-url", () -> "");
    registry.add("app.accounts.terms-version", () -> "v1");
    registry.add(
        "app.accounts.auth-token.secret", () -> "test-only-auth-token-secret-at-least-32-bytes");
    registry.add("app.accounts.admin.bootstrap-enabled", () -> "true");
    registry.add("app.accounts.admin.email", () -> "admin@health.test");
    registry.add("app.accounts.admin.password", () -> "test-only-admin-password");
  }

  @BeforeEach
  void authenticate() throws Exception {
    UUID adminId =
        jdbc.queryForObject(
            "SELECT id FROM accounts.users WHERE email = 'admin@health.test'", UUID.class);
    adminAccess = access(adminId);
    csrf = mvc.perform(get("/api/v1/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN").getValue();
  }

  private Cookie access(UUID userId) {
    return new Cookie("HA_ACCESS", tokens.issue(userId, Instant.now().plus(10, ChronoUnit.MINUTES)));
  }

  private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
    return request.cookie(adminAccess, new Cookie("XSRF-TOKEN", csrf)).header("X-XSRF-TOKEN", csrf);
  }

  private MockHttpServletRequestBuilder asOwner(MockHttpServletRequestBuilder request) {
    return request
        .cookie(access(OWNER), new Cookie("XSRF-TOKEN", csrf))
        .header("X-XSRF-TOKEN", csrf);
  }

  private static MockHttpServletRequestBuilder feedback(String messageId, String note, boolean share) {
    return post("/api/v1/chat/messages/{id}/feedback", messageId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"reason":"OTHER","note":"%s","shareConversation":%s}
            """
                .formatted(note, share));
  }

  private int feedbackRows(String note) {
    return jdbc.queryForObject("SELECT count(*) FROM chat.feedback WHERE note = ?", Integer.class, note);
  }

  // ---- UC-06 Chat ----

  @Test
  @Transactional
  @DisplayName("06-01 · UC06 · AI service chưa cấu hình: SSE báo lỗi, chỉ lưu tin nhắn người dùng")
  void chatWithoutConfiguredAiStreamsFailureAndKeepsOnlyUserTurn() throws Exception {
    String message = "[TEST] Service unavailable audit";
    var started =
        mvc.perform(
                asOwner(post("/api/v1/chat/messages"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"sessionId":"%s","message":"%s"}
                        """
                            .formatted(SESSION, message)))
            .andExpect(request().asyncStarted())
            .andReturn();
    mvc.perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
        .andExpect(content().string(containsString("AI_SERVICE_UNAVAILABLE")))
        .andExpect(content().string(containsString("\"status\":\"FAILED\"")));
    assertThat(
            jdbc.queryForList(
                "SELECT role, content FROM chat.messages WHERE session_id = ?::uuid ORDER BY created_at",
                SESSION))
        .hasSize(3)
        .last()
        .satisfies(row -> assertThat(row).containsEntry("role", "user").containsEntry("content", message));
  }

  // ---- UC-08 Đánh giá câu trả lời ----

  @Test
  @Transactional
  @DisplayName("08-02 · UC08 · API từ chối đánh giá tin nhắn role user và không lưu")
  void feedbackApiRejectsUserMessageWithoutSaving() throws Exception {
    String note = "UC08 user message audit";
    var response = mvc.perform(asOwner(feedback(USER_MESSAGE, note, true))).andReturn();
    int saved = feedbackRows(note);
    assertAll(
        () -> status().is4xxClientError().match(response),
        () -> assertThat(saved).as("feedback rows for the user message").isZero());
  }

  @Test
  @Transactional
  @DisplayName("08-03 · UC08 · Đánh giá câu trả lời có chia sẻ lưu đúng cặp hỏi–đáp")
  void feedbackOnAnswerWithOptInSavesSnapshot() throws Exception {
    String note = "UC08 assistant answer audit";
    mvc.perform(asOwner(feedback(ASSISTANT_MESSAGE, note, true)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reason").value("OTHER"))
        .andExpect(jsonPath("$.data.note").value(note))
        .andExpect(jsonPath("$.data.sharedQuestion").value(SEEDED_QUESTION))
        .andExpect(jsonPath("$.data.sharedAnswer").value(SEEDED_ANSWER));
    assertThat(
            jdbc.queryForList(
                "SELECT reason, shared_question, shared_answer FROM chat.feedback WHERE note = ?", note))
        .singleElement()
        .satisfies(
            row ->
                assertThat(row)
                    .containsEntry("reason", "OTHER")
                    .containsEntry("shared_question", SEEDED_QUESTION)
                    .containsEntry("shared_answer", SEEDED_ANSWER));
  }

  @Test
  @Transactional
  @DisplayName("08-04 · UC08 · Không chia sẻ thì không lưu nội dung hội thoại")
  void feedbackWithoutOptInDoesNotStoreConversation() throws Exception {
    String note = "UC08 opt out audit";
    mvc.perform(asOwner(feedback(ASSISTANT_MESSAGE, note, false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.note").value(note))
        .andExpect(jsonPath("$.data.sharedQuestion").doesNotExist())
        .andExpect(jsonPath("$.data.sharedAnswer").doesNotExist());
    assertThat(
            jdbc.queryForMap(
                "SELECT shared_question, shared_answer FROM chat.feedback WHERE note = ?", note))
        .containsEntry("shared_question", null)
        .containsEntry("shared_answer", null);
  }

  @Test
  @Transactional
  @DisplayName("08-05 · UC08 · Tài khoản khác không đánh giá được câu trả lời")
  void feedbackFromAnotherAccountDoesNotWrite() throws Exception {
    String note = "UC08 other account audit";
    mvc.perform(asAdmin(feedback(ASSISTANT_MESSAGE, note, true)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    assertThat(feedbackRows(note)).isZero();
  }

  @Test
  @Transactional
  @DisplayName("08-06 · UC08 · Đánh giá câu trả lời cũ không lấy lượt hỏi–đáp sau")
  void feedbackForEarlierAnswerExcludesLaterTurns() throws Exception {
    String note = "UC08 earlier answer audit";
    jdbc.update(
        """
        INSERT INTO chat.messages (id, session_id, role, content, created_at)
        VALUES (gen_random_uuid(), ?::uuid, 'user', '[TEST] Later question', '2026-10-29T00:01:00Z'),
               (gen_random_uuid(), ?::uuid, 'assistant', '[TEST] Later answer', '2026-10-29T00:01:01Z')
        """,
        SESSION,
        SESSION);
    mvc.perform(asOwner(feedback(ASSISTANT_MESSAGE, note, true)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sharedQuestion").value(SEEDED_QUESTION))
        .andExpect(jsonPath("$.data.sharedAnswer").value(SEEDED_ANSWER));
    assertThat(
            jdbc.queryForMap(
                "SELECT shared_question, shared_answer FROM chat.feedback WHERE note = ?", note))
        .containsEntry("shared_question", SEEDED_QUESTION)
        .containsEntry("shared_answer", SEEDED_ANSWER);
  }

  /** Not @Transactional: the 500 comes from a constraint violation inside the request. */
  @Test
  @DisplayName("08-07 · UC08 · Chia sẻ câu trả lời thiếu câu hỏi liền trước trả 4xx, không phải 500")
  void optInForAnswerWithoutPrecedingQuestionReturnsClientError() throws Exception {
    String note = "UC08 missing question audit";
    UUID answer = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO chat.messages (id, session_id, role, content, created_at)
        VALUES (?, ?::uuid, 'assistant', '[TEST] Historical answer without question',
                '2026-10-28T00:00:00Z')
        """,
        answer,
        SESSION);
    try {
      var response = mvc.perform(asOwner(feedback(answer.toString(), note, true))).andReturn();
      int saved = feedbackRows(note);
      assertAll(
          () -> status().is4xxClientError().match(response),
          () -> assertThat(saved).as("feedback rows for an answer without its question").isZero());
    } finally {
      jdbc.update("DELETE FROM chat.feedback WHERE note = ?", note);
      jdbc.update("DELETE FROM chat.messages WHERE id = ?", answer);
    }
  }

  // ---- UC-09 Lịch sử hội thoại ----

  @Test
  @Transactional
  @DisplayName("09-01 · UC08–09 · Xóa phiên xóa tin nhắn, giữ phản hồi ẩn danh đã chia sẻ")
  void deletingSessionCascadesMessagesAndKeepsAnonymousFeedback() throws Exception {
    String note = "UC08 UC09 delete session audit";
    mvc.perform(asOwner(feedback(ASSISTANT_MESSAGE, note, true))).andExpect(status().isOk());
    mvc.perform(asOwner(delete("/api/v1/chat/sessions/{id}", SESSION))).andExpect(status().isOk());
    mvc.perform(asOwner(get("/api/v1/chat/sessions/{id}/messages", SESSION)))
        .andExpect(status().isNotFound());
    mvc.perform(asOwner(feedback(ASSISTANT_MESSAGE, "feedback after deletion", true)))
        .andExpect(status().isNotFound());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM chat.sessions WHERE id = ?::uuid", Integer.class, SESSION))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM chat.messages WHERE session_id = ?::uuid", Integer.class, SESSION))
        .isZero();
    assertThat(
            jdbc.queryForMap(
                "SELECT shared_question, shared_answer FROM chat.feedback WHERE note = ?", note))
        .containsEntry("shared_question", SEEDED_QUESTION)
        .containsEntry("shared_answer", SEEDED_ANSWER);
    assertThat(feedbackRows("feedback after deletion")).isZero();
  }

  // ---- UC-10 Tra cứu kiến thức ----

  @Test
  @Transactional
  @DisplayName("10-01 · UC10 · Bài chuyển sang RETIRED biến mất khỏi danh sách và chi tiết")
  void retiringAnArticleRemovesItFromListAndDetail() throws Exception {
    String path = "/api/v1/knowledge/articles";
    String id = "00000000-0000-4000-8000-000000000010";
    mvc.perform(get(path + "/" + id)).andExpect(status().isOk());
    assertThat(
            jdbc.update("UPDATE knowledge.articles SET review_status = 'RETIRED' WHERE id = ?::uuid", id))
        .isEqualTo(1);
    mvc.perform(get(path + "/" + id))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(0));
  }

  @Test
  @Transactional
  @DisplayName("10-02 · UC10 · Bài APPROVED chỉ công khai khi nguồn đã có ngày xác minh")
  void approvingArticleAloneDoesNotPublishUntilSourceIsVerified() throws Exception {
    String path = "/api/v1/knowledge/articles";
    String topic = "Audit publish transition";
    UUID id = UUID.fromString("00000000-0000-4000-8000-000000000061");
    jdbc.update(
        """
        INSERT INTO knowledge.articles
          (id, title, topic, content, source_name, source_url, verified_at, updated_at, review_status)
        VALUES (?, '[TEST] UC10', ?, 'Nội dung kiểm thử UC-10', 'Synthetic fixture',
                'https://example.invalid/articles/uc10', NULL, TIMESTAMPTZ '2026-11-01 00:00:00Z',
                'DRAFT')
        """,
        id,
        topic);
    mvc.perform(get(path).param("topic", topic))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.meta.total").value(0));

    assertThat(
            jdbc.update("UPDATE knowledge.articles SET review_status = 'APPROVED' WHERE id = ?", id))
        .isEqualTo(1);
    mvc.perform(get(path + "/" + id)).andExpect(status().isNotFound());
    mvc.perform(get(path).param("topic", topic))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.meta.total").value(0));

    assertThat(
            jdbc.update(
                "UPDATE knowledge.articles SET verified_at = '2026-11-16T10:00:00Z' WHERE id = ?", id))
        .isEqualTo(1);
    mvc.perform(get(path).param("topic", topic))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.meta.total").value(1))
        .andExpect(jsonPath("$.data[0].id").value(id.toString()));
    mvc.perform(get(path + "/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.source.verifiedAt").value("2026-11-16T10:00:00.000Z"));
  }

  // ---- UC-11 Tra cứu cơ sở y tế ----

  @Test
  @Transactional
  @DisplayName("11-01 · UC11 · Bỏ ngày xác minh thì cơ sở y tế bị ẩn ngay")
  void revokingFacilityVerificationImmediatelyHidesIt() throws Exception {
    String path = "/api/v1/knowledge/facilities";
    String facility = "00000000-0000-4000-8000-000000000020";
    mvc.perform(get(path).param("q", "giả lập"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value(facility));
    assertThat(
            jdbc.update(
                "UPDATE knowledge.facilities SET verified_at = NULL WHERE id = ?::uuid", facility))
        .isEqualTo(1);
    mvc.perform(get(path).param("q", "giả lập"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isEmpty());
  }

  @Test
  @Transactional
  @DisplayName("11-02 · UC11 · Giới hạn 20 kết quả áp dụng sau khi lọc cơ sở đã xác minh")
  void facilityLimitIsAppliedAfterVerificationFilter() throws Exception {
    assertThat(
            jdbc.update(
                """
                INSERT INTO knowledge.facilities (id, name, specialties, province, address, official_url,
                  source_name, source_url, verified_at, is_synthetic)
                SELECT gen_random_uuid(), '[TEST] cap audit ' || lpad(n::text, 2, '0'),
                  ARRAY['[TEST]'], '[TEST]', '[TEST]', 'https://example.invalid',
                  'Synthetic fixture', 'https://example.invalid', CASE WHEN n > 0 THEN now() END, true
                FROM generate_series(0, 21) AS n
                """))
        .isEqualTo(22);
    mvc.perform(get("/api/v1/knowledge/facilities").param("q", "cap audit"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.meta.total").value(20))
        .andExpect(jsonPath("$.data.length()").value(20))
        .andExpect(jsonPath("$.data[0].name").value("[TEST] cap audit 01"))
        .andExpect(jsonPath("$.data[19].name").value("[TEST] cap audit 20"));
  }

  // ---- UC-18 Quản lý người dùng và phân quyền ----

  @Test
  @Transactional
  @DisplayName("18-01 · UC18 · Thu hồi role có hiệu lực ngay với cùng access cookie")
  void roleRevocationTakesEffectWithSameAccessCookie() throws Exception {
    String rolePath = "/api/v1/accounts/roles";
    String user = "00000000-0000-4000-8000-000000000002";
    String userRoles = "/api/v1/accounts/users/" + user + "/roles";
    Cookie userAccess = access(UUID.fromString(user));
    mvc.perform(get(rolePath).cookie(userAccess)).andExpect(status().isForbidden());
    mvc.perform(
            asAdmin(post(rolePath))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"key":"AUDIT_REVOCATION","name":"[TEST] Audit role","permissions":["RBAC_MANAGE"]}
                    """))
        .andExpect(status().isCreated());
    mvc.perform(
            asAdmin(put(userRoles))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"CONTENT_EXPERT\",\"AUDIT_REVOCATION\"]}"))
        .andExpect(status().isOk());
    mvc.perform(get(rolePath).cookie(userAccess)).andExpect(status().isOk());

    mvc.perform(
            asAdmin(put(userRoles))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"CONTENT_EXPERT\"]}"))
        .andExpect(status().isOk());
    mvc.perform(get(rolePath).cookie(userAccess))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    assertThat(
            jdbc.queryForList(
                "SELECT role FROM accounts.user_roles WHERE user_id = ?::uuid", String.class, user))
        .containsExactly("CONTENT_EXPERT");
  }
}
