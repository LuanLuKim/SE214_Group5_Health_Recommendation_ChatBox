package vn.edu.uit.se214.health.chat;

import java.util.UUID;

/** A private chat conversation session belonging to a specific user. */
public record ChatSession(UUID id, UUID userId, String createdAt) {}
