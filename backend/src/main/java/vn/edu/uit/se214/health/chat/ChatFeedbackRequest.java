package vn.edu.uit.se214.health.chat;

public record ChatFeedbackRequest(
    String reason,
    String note,
    boolean shareConversation) {}
