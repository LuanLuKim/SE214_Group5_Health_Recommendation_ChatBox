package vn.edu.uit.se214.health.chat;

import java.util.UUID;

/**
 * A private chat conversation session belonging to a specific user.
 * Supports UC-09 session listing, counting messages, and tracking the last exchanged timestamp.
 */
public record ChatSession(
    UUID id,
    UUID userId,
    String createdAt,
    int messageCount,
    String lastMessageAt) {

  public ChatSession(UUID id, UUID userId, String createdAt) {
    this(id, userId, createdAt, 0, null);
  }
}
