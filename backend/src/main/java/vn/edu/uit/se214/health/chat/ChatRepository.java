package vn.edu.uit.se214.health.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatRepository {
  ChatSession createSession(UUID userId);

  Optional<ChatSession> findSession(UUID sessionId);

  List<ChatSession> listSessions(UUID userId);

  boolean deleteSession(UUID sessionId, UUID userId);

  ChatMessage addMessage(UUID sessionId, String role, String content);

  Optional<ChatMessage> findMessage(UUID messageId);

  List<ChatMessage> listMessages(UUID sessionId, UUID userId);

  ChatFeedbackRecord createFeedback(String reason, String note, String sharedQuestion, String sharedAnswer);
}
