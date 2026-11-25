package chat;

import java.util.UUID;

/** Anonymous review snapshot of an answer. Contains no user, session, or message identifiers. */
public record ChatFeedbackRecord(
    UUID id,
    String reason,
    String note,
    String sharedQuestion,
    String sharedAnswer,
    String createdAt) {}
