package vn.edu.uit.se214.health.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.edu.uit.se214.health.shared.domain.Instants;

/** Parameterized SQL for schema chat. Only this module reads or writes its schema. */
@Repository
@Profile("postgres")
public class JdbcChatRepository implements ChatRepository {
  private final JdbcClient jdbc;

  public JdbcChatRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public ChatSession createSession(UUID userId) {
    UUID id = UUID.randomUUID();
    return jdbc.sql(
            """
            INSERT INTO chat.sessions (id, user_id, created_at)
            VALUES (?, ?, now())
            RETURNING id, user_id, created_at
            """)
        .params(id, userId)
        .query(this::mapSession)
        .single();
  }

  @Override
  public Optional<ChatSession> findSession(UUID sessionId) {
    return jdbc.sql("SELECT id, user_id, created_at FROM chat.sessions WHERE id = ?")
        .params(sessionId)
        .query(this::mapSession)
        .optional();
  }

  @Override
  public List<ChatSession> listSessions(UUID userId) {
    return jdbc.sql("SELECT id, user_id, created_at FROM chat.sessions WHERE user_id = ? ORDER BY created_at DESC")
        .params(userId)
        .query(this::mapSession)
        .list();
  }

  @Override
  public boolean deleteSession(UUID sessionId, UUID userId) {
    int updated =
        jdbc.sql("DELETE FROM chat.sessions WHERE id = ? AND user_id = ?")
            .params(sessionId, userId)
            .update();
    return updated > 0;
  }

  @Override
  public ChatMessage addMessage(UUID sessionId, String role, String content) {
    UUID id = UUID.randomUUID();
    return jdbc.sql(
            """
            INSERT INTO chat.messages (id, session_id, role, content, created_at)
            VALUES (?, ?, ?, ?, now())
            RETURNING id, session_id, role, content, created_at
            """)
        .params(id, sessionId, role, content)
        .query(this::mapMessage)
        .single();
  }

  @Override
  public Optional<ChatMessage> findMessage(UUID messageId) {
    return jdbc.sql("SELECT id, session_id, role, content, created_at FROM chat.messages WHERE id = ?")
        .params(messageId)
        .query(this::mapMessage)
        .optional();
  }

  @Override
  public List<ChatMessage> listMessages(UUID sessionId, UUID userId) {
    return jdbc.sql(
            """
            SELECT m.id, m.session_id, m.role, m.content, m.created_at
            FROM chat.messages m
            JOIN chat.sessions s ON m.session_id = s.id
            WHERE m.session_id = ? AND s.user_id = ?
            ORDER BY m.created_at ASC
            """)
        .params(sessionId, userId)
        .query(this::mapMessage)
        .list();
  }

  @Override
  public ChatFeedbackRecord createFeedback(
      String reason, String note, String sharedQuestion, String sharedAnswer) {
    UUID id = UUID.randomUUID();
    return jdbc.sql(
            """
            INSERT INTO chat.feedback (id, reason, note, shared_question, shared_answer, created_at)
            VALUES (?, ?, ?, ?, ?, now())
            RETURNING id, reason, note, shared_question, shared_answer, created_at
            """)
        .params(id, reason, note == null ? "" : note, sharedQuestion, sharedAnswer)
        .query(this::mapFeedback)
        .single();
  }

  private ChatSession mapSession(ResultSet rs, int rowNum) throws SQLException {
    return new ChatSession(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        Instants.iso(rs.getObject("created_at", OffsetDateTime.class)));
  }

  private ChatMessage mapMessage(ResultSet rs, int rowNum) throws SQLException {
    return new ChatMessage(
        rs.getObject("id", UUID.class),
        rs.getObject("session_id", UUID.class),
        rs.getString("role"),
        rs.getString("content"),
        Instants.iso(rs.getObject("created_at", OffsetDateTime.class)));
  }

  private ChatFeedbackRecord mapFeedback(ResultSet rs, int rowNum) throws SQLException {
    return new ChatFeedbackRecord(
        rs.getObject("id", UUID.class),
        rs.getString("reason"),
        rs.getString("note"),
        rs.getString("shared_question"),
        rs.getString("shared_answer"),
        Instants.iso(rs.getObject("created_at", OffsetDateTime.class)));
  }
}
