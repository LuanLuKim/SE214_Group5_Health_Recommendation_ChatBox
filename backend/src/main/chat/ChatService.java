package chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import accounts.api.ConsentQuery;
import integrations.PythonServiceClient;
import knowledge.api.FacilitySummary;
import knowledge.api.KnowledgeSearch;
import safety.api.CareLevel;
import safety.api.SafetyDecision;
import safety.api.SafetyEvaluator;
import shared.web.ApiException;

@Service
public class ChatService {
  private static final Logger log = LoggerFactory.getLogger(ChatService.class);
  private static final int MAX_MESSAGE_LENGTH = 4000;
  private static final long SSE_TIMEOUT_MS = 60_000L;
  private static final Pattern EMAIL_PATTERN =
      Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
  private static final Pattern PHONE_PATTERN =
      Pattern.compile("(?<!\\d)(?:\\+84|0)(?:[\\s.-]?\\d){9,10}(?!\\d)");

  private final ChatRepository repository;
  private final PythonServiceClient ai;
  private final ObjectProvider<SafetyEvaluator> safetyEvaluatorProvider;
  private final ObjectMapper json;
  private final ObjectProvider<ConsentQuery> consentQueries;
  private final Executor streamExecutor;
  private final ChatLexicon lexicon;
  private KnowledgeSearch knowledge;

  @Autowired
  public void setKnowledgeSearch(KnowledgeSearch knowledge) {
    this.knowledge = knowledge;
  }

  @Autowired
  public ChatService(
      ChatRepository repository,
      @Qualifier("aiService") PythonServiceClient ai,
      ObjectProvider<SafetyEvaluator> safetyEvaluatorProvider,
      ObjectMapper json,
      ObjectProvider<ConsentQuery> consentQueries,
      @Qualifier("chatStreamExecutor") ObjectProvider<Executor> streamExecutorProvider) {
    this.repository = repository;
    this.ai = ai;
    this.safetyEvaluatorProvider = safetyEvaluatorProvider;
    this.json = json;
    this.consentQueries = consentQueries;
    Executor providedExecutor =
        streamExecutorProvider != null ? streamExecutorProvider.getIfAvailable() : null;
    this.streamExecutor =
        providedExecutor != null
            ? providedExecutor
            : Executors.newFixedThreadPool(
                2,
                r -> {
                  Thread t = new Thread(r, "chat-sse-fallback");
                  t.setDaemon(true);
                  return t;
                });
    this.lexicon = ChatLexicon.loadDefault(json);
  }

  public ChatService(
      ChatRepository repository,
      PythonServiceClient ai,
      ObjectProvider<SafetyEvaluator> safetyEvaluatorProvider,
      ObjectMapper json,
      ObjectProvider<ConsentQuery> consentQueries) {
    this(repository, ai, safetyEvaluatorProvider, json, consentQueries, null);
  }

  public SseEmitter send(UUID userId, SendMessageRequest request) {
    requireConsent(userId);
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

    repository.addMessage(session.id(), "user", text);

    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

    if (!ai.configured()) {
      sendSseErrorAndComplete(
          emitter,
          "AI_SERVICE_UNAVAILABLE",
          "Dịch vụ AI chưa được cấu hình.");
      return emitter;
    }

    String normalizedText = ChatLexicon.normalize(text);
    Set<String> observedFacts = new LinkedHashSet<>(lexicon.extractFacts(normalizedText));
    if (request.facts() != null) {
      for (String fact : request.facts()) {
        if (fact != null && !fact.isBlank()) {
          observedFacts.add(fact.trim());
        }
      }
    }

    SafetyDecision safetyDecision = evaluateSafety(normalizedText, observedFacts);
    List<FacilitySummary> facilities = resolveFacilities(safetyDecision);

    Map<String, Object> payload = new HashMap<>();
    payload.put("session_id", session.id().toString());
    payload.put("message", text);
    payload.put("facts", List.copyOf(observedFacts));
    payload.put("safety_decision", safetyDecision);

    AtomicBoolean clientDisconnected = new AtomicBoolean(false);
    emitter.onTimeout(() -> clientDisconnected.set(true));
    emitter.onError(ignored -> clientDisconnected.set(true));
    emitter.onCompletion(() -> clientDisconnected.set(true));

    try {
      streamExecutor.execute(
          () -> streamFromAi(emitter, session.id(), payload, facilities, clientDisconnected));
    } catch (RejectedExecutionException ex) {
      log.warn("Chat stream executor queue full for session {}", session.id(), ex);
      sendSseErrorAndComplete(
          emitter,
          "AI_SERVICE_BUSY",
          "Hệ thống đang xử lý nhiều yêu cầu. Vui lòng thử lại sau giây lát.");
    }

    return emitter;
  }

  private void streamFromAi(
      SseEmitter emitter,
      UUID sessionId,
      Map<String, Object> payload,
      List<FacilitySummary> facilities,
      AtomicBoolean clientDisconnected) {
    StringBuilder assistantResponse = new StringBuilder();
    try {
      ai.postStream(
          "/v1/chat/answer",
          payload,
          line -> {
            if (clientDisconnected.get()) {
              throw new ClientDisconnectedException();
            }
            if (!line.startsWith("data: ")) {
              return;
            }
            String jsonStr = line.substring(6).trim();
            JsonNode eventNode = json.readTree(jsonStr);
            if ("safety".equals(eventNode.path("type").asText())
                && eventNode.path("data") instanceof ObjectNode data) {
              boolean evaluated =
                  "EVALUATED".equals(data.path("status").asText())
                      && !data.path("careLevel").isNull()
                      && !"INSUFFICIENT_INFO".equals(data.path("careLevel").asText());
              data.set("facilities", json.valueToTree(evaluated ? facilities : List.of()));
            }
            try {
              emitter.send(SseEmitter.event().data(json.writeValueAsString(eventNode)));
            } catch (IOException | IllegalStateException ex) {
              clientDisconnected.set(true);
              throw new ClientDisconnectedException(ex);
            }
            if ("token".equals(eventNode.path("type").asText())) {
              assistantResponse.append(eventNode.path("data").asText());
            }
          });

      if (!clientDisconnected.get() && !assistantResponse.isEmpty()) {
        repository.addMessage(sessionId, "assistant", assistantResponse.toString());
      }
      if (!clientDisconnected.get()) {
        emitter.complete();
      }
    } catch (Exception error) {
      if (isClientDisconnect(error)) {
        log.debug("Client disconnected during SSE stream for session {}", sessionId);
        return;
      }
      log.warn("AI streaming failed for session {}", sessionId, error);
      sendSseErrorAndComplete(
          emitter,
          "AI_SERVICE_ERROR",
          "Không thể tạo câu trả lời lúc này. Vui lòng thử lại sau.");
    }
  }

  private SafetyDecision evaluateSafety(String normalizedText, Set<String> observedFacts) {
    SafetyEvaluator evaluator =
        safetyEvaluatorProvider != null ? safetyEvaluatorProvider.getIfAvailable() : null;
    if (evaluator == null) {
      log.warn("No SafetyEvaluator available; failing closed to UNAVAILABLE");
      return SafetyDecision.unavailable();
    }
    try {
      SafetyDecision decision = evaluator.evaluateFacts(normalizedText, observedFacts);
      return decision != null ? decision : SafetyDecision.unavailable();
    } catch (Exception ex) {
      log.warn("SafetyEvaluator threw exception; failing closed to UNAVAILABLE", ex);
      return SafetyDecision.unavailable();
    }
  }

  private List<FacilitySummary> resolveFacilities(SafetyDecision decision) {
    if (knowledge == null
        || !"DATABASE_RESULTS".equals(knowledge.dataStatus())
        || decision.status() != SafetyDecision.Status.EVALUATED
        || decision.careLevel() == null
        || decision.careLevel() == CareLevel.INSUFFICIENT_INFO
        || decision.specialtyIds() == null
        || decision.specialtyIds().isEmpty()) {
      return List.of();
    }
    try {
      return knowledge.facilitiesBySpecialties(decision.specialtyIds());
    } catch (Exception ex) {
      log.warn("Failed to look up verified facilities; continuing without facilities", ex);
      return List.of();
    }
  }

  private static void sendSseErrorAndComplete(SseEmitter emitter, String code, String message) {
    try {
      String safeCode = code.replace("\"", "");
      String safeMessage = message.replace("\"", "");
      emitter.send(
          SseEmitter.event()
              .data(
                  "{\"type\":\"error\",\"data\":{\"code\":\""
                      + safeCode
                      + "\",\"message\":\""
                      + safeMessage
                      + "\",\"canRetry\":true}}"));
      emitter.send(SseEmitter.event().data("{\"type\":\"done\",\"data\":{\"status\":\"FAILED\"}}"));
      emitter.complete();
    } catch (Exception e) {
      emitter.completeWithError(e);
    }
  }

  private static boolean isClientDisconnect(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof ClientDisconnectedException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static final class ClientDisconnectedException extends RuntimeException {
    ClientDisconnectedException() {
      super("SSE client disconnected");
    }

    ClientDisconnectedException(Throwable cause) {
      super("SSE client disconnected", cause);
    }
  }

  public List<ChatSession> listSessions(UUID userId) {
    return repository.listSessions(userId);
  }

  public ChatSession getSession(UUID sessionId, UUID userId) {
    return repository
        .findSession(sessionId)
        .filter(s -> s.userId().equals(userId))
        .orElseThrow(() -> ApiException.notFound("Không tìm thấy phiên hội thoại."));
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

    if (!"assistant".equals(message.role())) {
      throw ApiException.invalidRequest("Chỉ có thể đánh giá câu trả lời của trợ lý.");
    }

    String sharedQuestion = null;
    String sharedAnswer = null;

    if (request.shareConversation()) {
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

      if (sharedQuestion == null || sharedQuestion.isBlank()) {
        throw ApiException.invalidRequest("Không tìm thấy câu hỏi liền trước để đính kèm hội thoại.");
      }
    }

    return repository.createFeedback(
        request.reason().trim(),
        request.note() != null ? request.note().trim() : "",
        sanitizePii(sharedQuestion),
        sanitizePii(sharedAnswer));
  }

  private static String sanitizePii(String text) {
    if (text == null) {
      return null;
    }
    String cleaned = EMAIL_PATTERN.matcher(text).replaceAll("[REDACTED_EMAIL]");
    return PHONE_PATTERN.matcher(cleaned).replaceAll("[REDACTED_PHONE]");
  }

  private void requireConsent(UUID userId) {
    ConsentQuery consents = consentQueries.getIfAvailable();
    if (consents == null) {
      throw ApiException.authNotConfigured();
    }
    if (!consents.healthDataProcessingAccepted(userId)) {
      throw ApiException.healthConsentRequired();
    }
  }
}
