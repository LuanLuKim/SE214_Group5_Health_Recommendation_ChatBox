package vn.edu.uit.se214.health.chat;

import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.edu.uit.se214.health.shared.web.ApiException;
import vn.edu.uit.se214.health.shared.web.ApiResponse;
import vn.edu.uit.se214.health.system.ModuleDescriptor;
import vn.edu.uit.se214.health.system.ModuleRegistry;

/**
 * M4 – UC-06, UC-08, UC-09. History and feedback live here; answering (Safety Gate first, RAG,
 * LLM, output checks, SSE streaming) is delegated to the FastAPI service through PythonServiceClient.
 * Every route below is private (SpringSecurityConfiguration); the user is the verified access
 * token's subject, never a value the client sends.
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
  private final ChatService chatService;

  public ChatController(ChatService chatService) {
    this.chatService = chatService;
  }

  @GetMapping
  public ApiResponse.Success<ModuleDescriptor> module() {
    return ApiResponse.of(ModuleRegistry.get("chat"));
  }

  @PostMapping(value = "/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter send(
      @AuthenticationPrincipal Jwt principal, @RequestBody(required = false) SendMessageRequest body) {
    return chatService.send(userId(principal), body);
  }

  @GetMapping("/sessions")
  public ApiResponse.Success<List<ChatSession>> sessions(@AuthenticationPrincipal Jwt principal) {
    List<ChatSession> list = chatService.listSessions(userId(principal));
    return ApiResponse.of(list, new ApiResponse.Meta("ok", list.size()));
  }

  @GetMapping("/sessions/{id}/messages")
  public ApiResponse.Success<List<ChatMessage>> messages(
      @AuthenticationPrincipal Jwt principal, @PathVariable String id) {
    UUID userId = userId(principal);
    List<ChatMessage> list = chatService.getMessages(parseUuid(id), userId);
    return ApiResponse.of(list, new ApiResponse.Meta("ok", list.size()));
  }

  @DeleteMapping("/sessions/{id}")
  public ApiResponse.Success<Void> deleteSession(
      @AuthenticationPrincipal Jwt principal, @PathVariable String id) {
    UUID userId = userId(principal);
    chatService.deleteSession(parseUuid(id), userId);
    return ApiResponse.of(null);
  }

  @PostMapping("/messages/{id}/feedback")
  public ApiResponse.Success<ChatFeedbackRecord> feedback(
      @AuthenticationPrincipal Jwt principal,
      @PathVariable String id,
      @RequestBody(required = false) ChatFeedbackRequest body) {
    UUID userId = userId(principal);
    return ApiResponse.of(chatService.submitFeedback(parseUuid(id), userId, body));
  }

  /** Fail closed: without a verified token subject the request is rejected, never mapped to a default user. */
  static UUID userId(Jwt principal) {
    if (principal == null || principal.getSubject() == null) throw ApiException.sessionInvalid();
    try {
      return UUID.fromString(principal.getSubject());
    } catch (IllegalArgumentException e) {
      throw ApiException.sessionInvalid();
    }
  }

  private static UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw ApiException.invalidRequest("ID không đúng định dạng UUID.");
    }
  }
}
