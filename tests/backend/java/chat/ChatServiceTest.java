package chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import accounts.api.ConsentQuery;
import integrations.PythonServiceClient;
import knowledge.api.FacilitySummary;
import knowledge.api.KnowledgeSearch;
import safety.api.CareLevel;
import safety.api.SafetyDecision;
import safety.api.SafetyEvaluator;
import shared.domain.SourceReference;
import shared.web.ApiException;

class ChatServiceTest {
  private InMemoryChatRepository repository;
  private PythonServiceClient aiClient;
  private ObjectProvider<SafetyEvaluator> safetyEvaluatorProvider;
  private SafetyEvaluator safetyEvaluator;
  private ObjectProvider<ConsentQuery> consentQueryProvider;
  private ConsentQuery consents;
  private ChatService chatService;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    repository = new InMemoryChatRepository();
    aiClient = mock(PythonServiceClient.class);
    safetyEvaluatorProvider = mock(ObjectProvider.class);
    safetyEvaluator = mock(SafetyEvaluator.class);
    when(safetyEvaluator.evaluateFacts(any(), any())).thenReturn(SafetyDecision.unavailable());
    when(safetyEvaluatorProvider.getIfAvailable()).thenReturn(safetyEvaluator);
    consentQueryProvider = mock(ObjectProvider.class);
    consents = mock(ConsentQuery.class);
    when(consents.healthDataProcessingAccepted(any(UUID.class))).thenReturn(true);
    when(consentQueryProvider.getIfAvailable()).thenReturn(consents);
    chatService =
        new ChatService(
            repository, aiClient, safetyEvaluatorProvider, new ObjectMapper(), consentQueryProvider);
  }

  @Test
  void sendRequiresHealthDataConsent() {
    UUID userId = UUID.randomUUID();
    when(consents.healthDataProcessingAccepted(userId)).thenReturn(false);

    assertThatThrownBy(
            () ->
                chatService.send(
                    userId, new SendMessageRequest(null, "Tôi bị sốt", List.of())))
        .isInstanceOf(ApiException.class)
        .satisfies(
            error -> {
              assertThat(((ApiException) error).status()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
              assertThat(((ApiException) error).code()).isEqualTo("HEALTH_CONSENT_REQUIRED");
            });
  }

  @Test
  void sendRejectsEmptyOrTooLongMessages() {
    UUID userId = UUID.randomUUID();
    assertThatThrownBy(() -> chatService.send(userId, new SendMessageRequest(null, "", List.of())))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Tin nhắn không được để trống");

    assertThatThrownBy(() -> chatService.send(userId, new SendMessageRequest(null, "a".repeat(4001), List.of())))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Tin nhắn vượt quá độ dài tối đa");
  }

  @Test
  void sendCreatesSessionAndPersistsUserAndAssistantMessages() throws Exception {
    UUID userId = UUID.randomUUID();
    when(aiClient.configured()).thenReturn(true);

    doAnswer(
            invocation -> {
              PythonServiceClient.StreamConsumer consumer = invocation.getArgument(2);
              consumer.onLine("data: {\"type\":\"token\",\"data\":\"Xin chào! \"}");
              consumer.onLine("data: {\"type\":\"token\",\"data\":\"Tôi có thể giúp gì cho bạn?\"}");
              consumer.onLine("data: {\"type\":\"done\",\"data\":{\"status\":\"COMPLETED\"}}");
              return null;
            })
        .when(aiClient)
        .postStream(eq("/v1/chat/answer"), any(), any());

    SseEmitter emitter = chatService.send(userId, new SendMessageRequest(null, "Tôi bị sốt nhẹ", List.of()));
    assertThat(emitter).isNotNull();

    // Verify session was created
    List<ChatSession> sessions = chatService.listSessions(userId);
    assertThat(sessions).hasSize(1);
    UUID sessionId = sessions.get(0).id();

    // Wait briefly for async consumer to finish
    Thread.sleep(150);

    // Verify messages persisted
    List<ChatMessage> messages = chatService.getMessages(sessionId, userId);
    assertThat(messages).hasSize(2);
    assertThat(messages.get(0).role()).isEqualTo("user");
    assertThat(messages.get(0).content()).isEqualTo("Tôi bị sốt nhẹ");
    assertThat(messages.get(1).role()).isEqualTo("assistant");
    assertThat(messages.get(1).content()).isEqualTo("Xin chào! Tôi có thể giúp gì cho bạn?");
  }

  @Test
  @SuppressWarnings("unchecked")
  void sendDelegatesSafetyToM1EvaluatorAndOmitsRulesPayload() throws Exception {
    UUID userId = UUID.randomUUID();
    when(aiClient.configured()).thenReturn(true);

    SafetyDecision emergency =
        new SafetyDecision(
            SafetyDecision.Status.EVALUATED,
            CareLevel.EMERGENCY_NOW,
            List.of("rule-emerg"),
            List.of(),
            List.of("cardiology"),
            List.of(
                new SourceReference(
                    "src-1", "[TEST] Source", "https://example.invalid/rule", "2026-11-01T00:00:00Z")));
    when(safetyEvaluator.evaluateFacts(any(), any())).thenReturn(emergency);

    KnowledgeSearch knowledge = mock(KnowledgeSearch.class);
    when(knowledge.dataStatus()).thenReturn("DATABASE_RESULTS");
    when(knowledge.facilitiesBySpecialties(List.of("cardiology")))
        .thenReturn(
            List.of(
                new FacilitySummary(
                    "fac-1",
                    "[TEST] Bệnh viện mẫu",
                    List.of("cardiology"),
                    "TP.HCM",
                    "Địa chỉ mẫu",
                    "https://example.invalid/fac",
                    new SourceReference(
                        "fac-1", "[TEST] Source", "https://example.invalid/fac", "2026-11-01T00:00:00Z"))));
    chatService.setKnowledgeSearch(knowledge);

    AtomicReference<Map<String, Object>> capturedPayload = new AtomicReference<>();
    CountDownLatch streamFinished = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              try {
                capturedPayload.set(invocation.getArgument(1));
                PythonServiceClient.StreamConsumer consumer = invocation.getArgument(2);
                consumer.onLine("data: {\"type\":\"safety\",\"data\":{\"status\":\"EVALUATED\",\"careLevel\":\"EMERGENCY_NOW\",\"specialtyIds\":[\"cardiology\"]}}");
                consumer.onLine("data: {\"type\":\"token\",\"data\":\"Hãy gọi 115 ngay.\"}");
                consumer.onLine("data: {\"type\":\"done\",\"data\":{\"status\":\"EMERGENCY_HANDLED\"}}");
              } finally {
                streamFinished.countDown();
              }
              return null;
            })
        .when(aiClient)
        .postStream(eq("/v1/chat/answer"), any(), any());

    chatService.send(
        userId, new SendMessageRequest(null, "Cho tôi hỏi về đau ngực và khó thở", List.of("custom_fact")));
    assertThat(streamFinished.await(2, TimeUnit.SECONDS)).isTrue();

    verify(safetyEvaluator)
        .evaluateFacts(
            eq("Cho tôi hỏi về đau ngực và khó thở"),
            eq(Set.of("chest_pain", "breathlessness", "custom_fact")));

    Map<String, Object> payload = capturedPayload.get();
    assertThat(payload).containsKey("safety_decision").doesNotContainKey("rules");
    assertThat(payload.get("safety_decision")).isEqualTo(emergency);
    verify(knowledge).facilitiesBySpecialties(List.of("cardiology"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void sendFailsClosedToUnavailableWhenSafetyEvaluatorThrows() throws Exception {
    UUID userId = UUID.randomUUID();
    when(aiClient.configured()).thenReturn(true);
    when(safetyEvaluator.evaluateFacts(any(), any()))
        .thenThrow(new IllegalStateException("database offline"));

    AtomicReference<Map<String, Object>> capturedPayload = new AtomicReference<>();
    CountDownLatch streamFinished = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              try {
                capturedPayload.set(invocation.getArgument(1));
                PythonServiceClient.StreamConsumer consumer = invocation.getArgument(2);
                consumer.onLine("data: {\"type\":\"done\",\"data\":{\"status\":\"COMPLETED\"}}");
              } finally {
                streamFinished.countDown();
              }
              return null;
            })
        .when(aiClient)
        .postStream(eq("/v1/chat/answer"), any(), any());

    chatService.send(userId, new SendMessageRequest(null, "Tôi bị sốt", List.of()));
    assertThat(streamFinished.await(2, TimeUnit.SECONDS)).isTrue();

    Map<String, Object> payload = capturedPayload.get();
    assertThat(payload.get("safety_decision")).isEqualTo(SafetyDecision.unavailable());
  }

  @Test
  void sendStopsReadingAiStreamWhenClientDisconnects() throws Exception {
    UUID userId = UUID.randomUUID();
    when(aiClient.configured()).thenReturn(true);

    AtomicInteger linesConsumed = new AtomicInteger(0);
    AtomicBoolean abortedStream = new AtomicBoolean(false);
    SseEmitter[] emitterHolder = new SseEmitter[1];
    CountDownLatch emitterReady = new CountDownLatch(1);
    CountDownLatch streamFinished = new CountDownLatch(1);

    doAnswer(
            invocation -> {
              PythonServiceClient.StreamConsumer consumer = invocation.getArgument(2);
              try {
                emitterReady.await(2, TimeUnit.SECONDS);
                linesConsumed.incrementAndGet();
                consumer.onLine("data: {\"type\":\"token\",\"data\":\"Phần 1\"}");
                // Simulate disconnect/completion before chunk 2; next emitter.send throws IllegalStateException
                emitterHolder[0].complete();
                linesConsumed.incrementAndGet();
                consumer.onLine("data: {\"type\":\"token\",\"data\":\"Phần 2\"}");
              } catch (Exception ex) {
                abortedStream.set(true);
                throw ex;
              } finally {
                streamFinished.countDown();
              }
              return null;
            })
        .when(aiClient)
        .postStream(eq("/v1/chat/answer"), any(), any());

    emitterHolder[0] =
        chatService.send(userId, new SendMessageRequest(null, "Tôi bị sốt nhẹ", List.of()));
    emitterReady.countDown();

    assertThat(streamFinished.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(abortedStream.get()).isTrue();
    assertThat(linesConsumed.get()).isEqualTo(2);
  }

  @Test
  void lexiconUsesUnicodeWordBoundariesWithoutSubstringFalsePositives() {
    ChatLexicon lexicon = ChatLexicon.loadDefault(new ObjectMapper());
    assertThat(lexicon.extractFacts("Xin cho tôi hỏi nhỏ về họ hàng")).doesNotContain("cough");
    assertThat(lexicon.extractFacts("Tôi bị ho khan")).contains("cough");
  }

  @Test
  void getMessagesAndListSessionsEnforceOwnership() {
    UUID user1 = UUID.randomUUID();
    UUID user2 = UUID.randomUUID();

    ChatSession session1 = repository.createSession(user1);
    repository.addMessage(session1.id(), "user", "Hello from user 1");

    assertThat(chatService.listSessions(user1)).hasSize(1);
    assertThat(chatService.listSessions(user2)).isEmpty();

    assertThatThrownBy(() -> chatService.getMessages(session1.id(), user2))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Không tìm thấy phiên hội thoại");

    assertThat(chatService.getMessages(session1.id(), user1)).hasSize(1);
  }

  @Test
  void deleteSessionOnlyWorksForOwner() {
    UUID user1 = UUID.randomUUID();
    UUID user2 = UUID.randomUUID();

    ChatSession session = repository.createSession(user1);

    assertThatThrownBy(() -> chatService.deleteSession(session.id(), user2))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Không tìm thấy phiên hội thoại");

    assertThat(chatService.deleteSession(session.id(), user1)).isTrue();
    assertThat(chatService.listSessions(user1)).isEmpty();
  }

  @Test
  void sessionCountsMessagesAndTracksLastExchangeEvenForEmptySession() {
    UUID userId = UUID.randomUUID();
    ChatSession session = repository.createSession(userId);

    // Empty session has 0 messages and null lastMessageAt
    ChatSession fetchedEmpty = chatService.getSession(session.id(), userId);
    assertThat(fetchedEmpty.messageCount()).isZero();
    assertThat(fetchedEmpty.lastMessageAt()).isNull();

    List<ChatSession> listedEmpty = chatService.listSessions(userId);
    assertThat(listedEmpty).hasSize(1);
    assertThat(listedEmpty.get(0).messageCount()).isZero();
    assertThat(listedEmpty.get(0).lastMessageAt()).isNull();

    // Add 1st message
    ChatMessage msg1 = repository.addMessage(session.id(), "user", "Tin nhắn 1");
    ChatSession fetched1 = chatService.getSession(session.id(), userId);
    assertThat(fetched1.messageCount()).isEqualTo(1);
    assertThat(fetched1.lastMessageAt()).isEqualTo(msg1.createdAt());

    // Add 2nd message
    ChatMessage msg2 = repository.addMessage(session.id(), "assistant", "Tin nhắn 2");
    ChatSession fetched2 = chatService.getSession(session.id(), userId);
    assertThat(fetched2.messageCount()).isEqualTo(2);
    assertThat(fetched2.lastMessageAt()).isEqualTo(msg2.createdAt());

    List<ChatSession> listed = chatService.listSessions(userId);
    assertThat(listed).hasSize(1);
    assertThat(listed.get(0).messageCount()).isEqualTo(2);
    assertThat(listed.get(0).lastMessageAt()).isEqualTo(msg2.createdAt());
  }

  @Test
  void getSessionEnforcesOwnership() {
    UUID user1 = UUID.randomUUID();
    UUID user2 = UUID.randomUUID();
    ChatSession session = repository.createSession(user1);

    assertThatThrownBy(() -> chatService.getSession(session.id(), user2))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Không tìm thấy phiên hội thoại");

    assertThat(chatService.getSession(session.id(), user1).id()).isEqualTo(session.id());
  }

  @Test
  void deleteSessionIsPermanentAndCleansUpEverything() {
    UUID userId = UUID.randomUUID();
    ChatSession session = repository.createSession(userId);
    repository.addMessage(session.id(), "user", "Câu hỏi kiểm thử");

    assertThat(chatService.deleteSession(session.id(), userId)).isTrue();

    // Subsequent access returns not found
    assertThatThrownBy(() -> chatService.getSession(session.id(), userId))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Không tìm thấy phiên hội thoại");

    assertThatThrownBy(() -> chatService.getMessages(session.id(), userId))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Không tìm thấy phiên hội thoại");

    assertThatThrownBy(() -> chatService.deleteSession(session.id(), userId))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Không tìm thấy phiên hội thoại");
  }

  @Test
  void submitFeedbackAttachesPrecedingUserQuestionWhenOptedIn() {
    UUID userId = UUID.randomUUID();
    ChatSession session = repository.createSession(userId);
    ChatMessage userMsg = repository.addMessage(session.id(), "user", "Tôi bị sốt nên uống gì?");
    ChatMessage assistantMsg = repository.addMessage(session.id(), "assistant", "Bạn nên uống nhiều nước.");

    // With shareConversation = true
    ChatFeedbackRecord feedback =
        chatService.submitFeedback(
            assistantMsg.id(), userId, new ChatFeedbackRequest("INCORRECT", "Cần chi tiết hơn", true));

    assertThat(feedback.reason()).isEqualTo("INCORRECT");
    assertThat(feedback.note()).isEqualTo("Cần chi tiết hơn");
    assertThat(feedback.sharedQuestion()).isEqualTo("Tôi bị sốt nên uống gì?");
    assertThat(feedback.sharedAnswer()).isEqualTo("Bạn nên uống nhiều nước.");

    // With shareConversation = false
    ChatFeedbackRecord feedbackNoShare =
        chatService.submitFeedback(
            assistantMsg.id(), userId, new ChatFeedbackRequest("OTHER", "Góp ý chung", false));

    assertThat(feedbackNoShare.sharedQuestion()).isNull();
    assertThat(feedbackNoShare.sharedAnswer()).isNull();
  }

  @Test
  void submitFeedbackRejectsUserMessageRole() {
    UUID userId = UUID.randomUUID();
    ChatSession session = repository.createSession(userId);
    ChatMessage userMsg = repository.addMessage(session.id(), "user", "Tin nhắn câu hỏi");

    assertThatThrownBy(
            () ->
                chatService.submitFeedback(
                    userMsg.id(), userId, new ChatFeedbackRequest("OTHER", "Đánh giá nhầm", false)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Chỉ có thể đánh giá câu trả lời của trợ lý");
  }

  @Test
  void submitFeedbackRejectsOptInWhenNoPrecedingQuestion() {
    UUID userId = UUID.randomUUID();
    ChatSession session = repository.createSession(userId);
    ChatMessage assistantMsg = repository.addMessage(session.id(), "assistant", "Câu trả lời mồ côi.");

    assertThatThrownBy(
            () ->
                chatService.submitFeedback(
                    assistantMsg.id(), userId, new ChatFeedbackRequest("OTHER", "Không có câu hỏi", true)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Không tìm thấy câu hỏi liền trước");
  }

  @Test
  void submitFeedbackSanitizesEmailAndPhoneNumber() {
    UUID userId = UUID.randomUUID();
    ChatSession session = repository.createSession(userId);
    repository.addMessage(
        session.id(), "user", "Email của tôi là benhnhan@gmail.com và sđt 0987654321, tôi bị sốt.");
    ChatMessage assistantMsg =
        repository.addMessage(
            session.id(), "assistant", "Bạn có thể liên hệ tổng đài hỗ trợ 02812345678 hoặc support@med.vn.");

    ChatFeedbackRecord feedback =
        chatService.submitFeedback(
            assistantMsg.id(), userId, new ChatFeedbackRequest("OTHER", "Cần bảo mật", true));

    assertThat(feedback.sharedQuestion())
        .isEqualTo("Email của tôi là [REDACTED_EMAIL] và sđt [REDACTED_PHONE], tôi bị sốt.");
    assertThat(feedback.sharedAnswer())
        .isEqualTo("Bạn có thể liên hệ tổng đài hỗ trợ [REDACTED_PHONE] hoặc [REDACTED_EMAIL].");
  }
}
