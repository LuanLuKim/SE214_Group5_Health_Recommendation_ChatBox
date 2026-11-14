package vn.edu.uit.se214.health.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.edu.uit.se214.health.integrations.PythonServiceClient;
import vn.edu.uit.se214.health.safety.ApprovedSafetyRule;
import vn.edu.uit.se214.health.safety.RuleRepository;
import vn.edu.uit.se214.health.shared.web.ApiException;

@Service
public class ChatService {
  private static final int MAX_MESSAGE_LENGTH = 4000;
  private static final long SSE_TIMEOUT_MS = 60_000L;

  private final ChatRepository repository;
  private final PythonServiceClient ai;
  private final ObjectProvider<RuleRepository> ruleRepositoryProvider;
  private final ObjectMapper json;

  public ChatService(
      ChatRepository repository,
      @Qualifier("aiService") PythonServiceClient ai,
      ObjectProvider<RuleRepository> ruleRepositoryProvider,
      ObjectMapper json) {
    this.repository = repository;
    this.ai = ai;
    this.ruleRepositoryProvider = ruleRepositoryProvider;
    this.json = json;
  }

  public SseEmitter send(UUID userId, SendMessageRequest request) {
    if (request == null || request.message() == null || request.message().trim().isEmpty()) {
      throw ApiException.invalidRequest("Tin nhắn không được để trống.");
    }
    String text = request.message().trim();
    if (text.length() > MAX_MESSAGE_LENGTH) {
      throw ApiException.invalidRequest("Tin nhắn vượt quá độ dài tối đa (4000 ký tự).");
    }

    ChatSession session;
    if (request.sessionId() == null) {
      session = repository.createSession(userId);
    } else {
      session =
          repository
              .findSession(request.sessionId())
              .filter(s -> s.userId().equals(userId))
              .orElseThrow(() -> ApiException.notFound("Không tìm thấy phiên hội thoại."));
    }

    // Persist user message
    repository.addMessage(session.id(), "user", text);

    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

    // Load evaluable safety rules from M1 if rule repository exists
    List<ApprovedSafetyRule> rules = Collections.emptyList();
    RuleRepository ruleRepository = ruleRepositoryProvider.getIfAvailable();
    if (ruleRepository != null) {
      try {
        rules = ruleRepository.listEvaluable();
      } catch (Exception ignored) {
        // Safe fail-closed
      }
    }

    Map<String, Object> payload = new HashMap<>();
    payload.put("session_id", session.id().toString());
    payload.put("message", text);
    payload.put("facts", request.facts() != null ? request.facts() : List.of());
    payload.put("rules", rules);

    if (!ai.configured()) {
      try {
        emitter.send(
            SseEmitter.event()
                .data(
                    "{\"type\":\"error\",\"data\":{\"code\":\"AI_SERVICE_UNAVAILABLE\",\"message\":\"Dịch vụ AI chưa được cấu hình.\",\"canRetry\":true}}"));
        emitter.send(SseEmitter.event().data("{\"type\":\"done\",\"data\":{\"status\":\"FAILED\"}}"));
        emitter.complete();
      } catch (Exception e) {
        emitter.completeWithError(e);
      }
      return emitter;
    }

    CompletableFuture.runAsync(
        () -> {
          StringBuilder assistantResponse = new StringBuilder();
          try {
            ai.postStream(
                "/v1/chat/answer",
                payload,
                line -> {
                  if (line.startsWith("data: ")) {
                    String jsonStr = line.substring(6).trim();
                    try {
                      emitter.send(SseEmitter.event().data(jsonStr));
                      JsonNode eventNode = json.readTree(jsonStr);
                      if ("token".equals(eventNode.path("type").asText())) {
                        assistantResponse.append(eventNode.path("data").asText());
                      }
                    } catch (Exception ignored) {
                      // Client disconnected
                    }
                  }
                });

            if (!assistantResponse.isEmpty()) {
              repository.addMessage(session.id(), "assistant", assistantResponse.toString());
            }
            emitter.complete();
          } catch (Exception error) {
            try {
              emitter.send(
                  SseEmitter.event()
                      .data(
                          "{\"type\":\"error\",\"data\":{\"code\":\"AI_SERVICE_ERROR\",\"message\":\"Không thể tạo câu trả lời lúc này. Vui lòng thử lại sau.\",\"canRetry\":true}}"));
              emitter.send(SseEmitter.event().data("{\"type\":\"done\",\"data\":{\"status\":\"FAILED\"}}"));
              emitter.complete();
            } catch (Exception ignored) {
              emitter.completeWithError(error);
            }
          }
        });

    return emitter;
  }

  public List<ChatSession> listSessions(UUID userId) {
    return repository.listSessions(userId);
  }

  public List<ChatMessage> getMessages(UUID sessionId, UUID userId) {
    repository
        .findSession(sessionId)
        .filter(s -> s.userId().equals(userId))
        .orElseThrow(() -> ApiException.notFound("Không tìm thấy phiên hội thoại."));
    return repository.listMessages(sessionId, userId);
  }

  public boolean deleteSession(UUID sessionId, UUID userId) {
    boolean deleted = repository.deleteSession(sessionId, userId);
    if (!deleted) {
      throw ApiException.notFound("Không tìm thấy phiên hội thoại.");
    }
    return true;
  }

  public ChatFeedbackRecord submitFeedback(
      UUID messageId, UUID userId, ChatFeedbackRequest request) {
    if (request == null || request.reason() == null || request.reason().trim().isEmpty()) {
      throw ApiException.invalidRequest("Lý do đánh giá không được để trống.");
    }

    ChatMessage message =
        repository
            .findMessage(messageId)
            .orElseThrow(() -> ApiException.notFound("Không tìm thấy tin nhắn."));

    ChatSession session =
        repository
            .findSession(message.sessionId())
            .filter(s -> s.userId().equals(userId))
            .orElseThrow(() -> ApiException.notFound("Không tìm thấy tin nhắn."));

    String sharedQuestion = null;
    String sharedAnswer = null;

    if (request.shareConversation()) {
      // Find the user question in this session immediately preceding this assistant message
      List<ChatMessage> sessionMessages = repository.listMessages(session.id(), userId);
      String lastUserQuestion = null;
      for (ChatMessage m : sessionMessages) {
        if ("user".equals(m.role())) {
          lastUserQuestion = m.content();
        } else if (m.id().equals(messageId)) {
          sharedQuestion = lastUserQuestion;
          sharedAnswer = m.content();
          break;
        }
      }
    }

    return repository.createFeedback(
        request.reason().trim(),
        request.note() != null ? request.note().trim() : "",
        sharedQuestion,
        sharedAnswer);
  }
}
