package chat;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import shared.domain.Instants;

/** DATA_MODE=mock. In-memory storage for development without PostgreSQL. */
@Repository
@Profile("!postgres")
public class InMemoryChatRepository implements ChatRepository {
  private final Map<UUID, ChatSession> sessions = new ConcurrentHashMap<>();
  private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
  private final List<ChatFeedbackRecord> feedbacks = new CopyOnWriteArrayList<>();

  @Override
  public ChatSession createSession(UUID userId) {
    UUID id = UUID.randomUUID();
    ChatSession session = new ChatSession(id, userId, Instants.iso(OffsetDateTime.now()));
    sessions.put(id, session);
    return session;
  }

  @Override
  public Optional<ChatSession> findSession(UUID sessionId) {
    ChatSession session = sessions.get(sessionId);
    if (session == null) {
      return Optional.empty();
    }
    List<ChatMessage> sessionMsgs =
        messages.stream().filter(m -> m.sessionId().equals(sessionId)).toList();
    int count = sessionMsgs.size();
    String lastMsgAt = sessionMsgs.isEmpty() ? null : sessionMsgs.get(sessionMsgs.size() - 1).createdAt();
    return Optional.of(new ChatSession(session.id(), session.userId(), session.createdAt(), count, lastMsgAt));
  }

  @Override
  public List<ChatSession> listSessions(UUID userId) {
    return sessions.values().stream()
        .filter(s -> s.userId().equals(userId))
        .map(
            s -> {
              List<ChatMessage> sessionMsgs =
                  messages.stream().filter(m -> m.sessionId().equals(s.id())).toList();
              int count = sessionMsgs.size();
              String lastMsgAt =
                  sessionMsgs.isEmpty() ? null : sessionMsgs.get(sessionMsgs.size() - 1).createdAt();
              return new ChatSession(s.id(), s.userId(), s.createdAt(), count, lastMsgAt);
            })
        .sorted(
            Comparator.comparing(
                    (ChatSession s) -> s.lastMessageAt() != null ? s.lastMessageAt() : s.createdAt())
                .reversed())
        .toList();
  }

  @Override
  public boolean deleteSession(UUID sessionId, UUID userId) {
    ChatSession session = sessions.get(sessionId);
    if (session != null && session.userId().equals(userId)) {
      sessions.remove(sessionId);
      messages.removeIf(m -> m.sessionId().equals(sessionId));
      return true;
    }
    return false;
  }

  @Override
  public ChatMessage addMessage(UUID sessionId, String role, String content) {
    UUID id = UUID.randomUUID();
    ChatMessage message = new ChatMessage(id, sessionId, role, content, Instants.iso(OffsetDateTime.now()));
    messages.add(message);
    return message;
  }

  @Override
  public Optional<ChatMessage> findMessage(UUID messageId) {
    return messages.stream().filter(m -> m.id().equals(messageId)).findFirst();
  }

  @Override
  public List<ChatMessage> listMessages(UUID sessionId, UUID userId) {
    ChatSession session = sessions.get(sessionId);
    if (session == null || !session.userId().equals(userId)) {
      return List.of();
    }
    return messages.stream()
        .filter(m -> m.sessionId().equals(sessionId))
        .sorted(Comparator.comparing(ChatMessage::createdAt))
        .toList();
  }

  @Override
  public ChatFeedbackRecord createFeedback(
      String reason, String note, String sharedQuestion, String sharedAnswer) {
    UUID id = UUID.randomUUID();
    ChatFeedbackRecord feedback =
        new ChatFeedbackRecord(
            id,
            reason,
            note == null ? "" : note,
            sharedQuestion,
            sharedAnswer,
            Instants.iso(OffsetDateTime.now()));
    feedbacks.add(feedback);
    return feedback;
  }
}
