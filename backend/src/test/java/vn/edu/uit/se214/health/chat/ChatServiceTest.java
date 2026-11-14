package vn.edu.uit.se214.health.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.edu.uit.se214.health.integrations.PythonServiceClient;
import vn.edu.uit.se214.health.safety.RuleRepository;
import vn.edu.uit.se214.health.shared.web.ApiException;

class ChatServiceTest {
  private InMemoryChatRepository repository;
  private PythonServiceClient aiClient;
  private ObjectProvider<RuleRepository> ruleRepositoryProvider;
  private ChatService chatService;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    repository = new InMemoryChatRepository();
    aiClient = mock(PythonServiceClient.class);
    ruleRepositoryProvider = mock(ObjectProvider.class);
    when(ruleRepositoryProvider.getIfAvailable()).thenReturn(null);
    chatService = new ChatService(repository, aiClient, ruleRepositoryProvider, new ObjectMapper());
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
}
