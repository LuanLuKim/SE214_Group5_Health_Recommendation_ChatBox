package vn.edu.uit.se214.health.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import vn.edu.uit.se214.health.accounts.api.ConsentQuery;
import vn.edu.uit.se214.health.chat.ChatFeedbackRequest;
import vn.edu.uit.se214.health.chat.ChatMessage;
import vn.edu.uit.se214.health.chat.ChatService;
import vn.edu.uit.se214.health.chat.ChatSession;
import vn.edu.uit.se214.health.chat.InMemoryChatRepository;
import vn.edu.uit.se214.health.integrations.PythonServiceClient;
import vn.edu.uit.se214.health.safety.RuleRepository;
import vn.edu.uit.se214.health.shared.web.ApiException;

/** Backend audit round 1, service-level case. See README.md in this package. */
class ChatFeedbackServiceAuditTest {
  private InMemoryChatRepository repository;
  private ChatService chatService;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    repository = new InMemoryChatRepository();
    ObjectProvider<RuleRepository> rules = mock(ObjectProvider.class);
    ObjectProvider<ConsentQuery> consentProvider = mock(ObjectProvider.class);
    ConsentQuery consents = mock(ConsentQuery.class);
    when(consents.healthDataProcessingAccepted(any(UUID.class))).thenReturn(true);
    when(consentProvider.getIfAvailable()).thenReturn(consents);
    when(rules.getIfAvailable()).thenReturn(null);
    chatService =
        new ChatService(
            repository, mock(PythonServiceClient.class), rules, new ObjectMapper(), consentProvider);
  }

  @Test
  @KnownBug("#152")
  @DisplayName("08-01 · UC08 · Service từ chối đánh giá tin nhắn role user")
  void feedbackOnlyAcceptsAssistantAnswers() {
    UUID userId = UUID.randomUUID();
    ChatSession session = repository.createSession(userId);
    ChatMessage userMessage = repository.addMessage(session.id(), "user", "Câu hỏi kiểm thử");

    assertThatThrownBy(
            () ->
                chatService.submitFeedback(
                    userMessage.id(), userId, new ChatFeedbackRequest("OTHER", "Kiểm thử", false)))
        .isInstanceOf(ApiException.class);
  }
}
