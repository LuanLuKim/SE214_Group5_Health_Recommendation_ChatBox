package chat;

public record ChatFeedbackRequest(
    String reason,
    String note,
    boolean shareConversation) {}
