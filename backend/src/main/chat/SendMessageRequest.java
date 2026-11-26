package chat;

import java.util.List;
import java.util.UUID;

public record SendMessageRequest(
    UUID sessionId,
    String message,
    List<String> facts) {}
