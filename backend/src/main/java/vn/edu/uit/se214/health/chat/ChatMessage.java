package vn.edu.uit.se214.health.chat;

import java.util.UUID;

/** A single chat message within a session. Role is either 'user' or 'assistant'. */
public record ChatMessage(
    UUID id,
    UUID sessionId,
    String role,
    String content,
    String createdAt) {}
