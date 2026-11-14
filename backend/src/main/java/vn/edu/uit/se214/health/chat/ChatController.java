package vn.edu.uit.se214.health.chat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
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
import vn.edu.uit.se214.health.shared.web.RequiresSession;
import vn.edu.uit.se214.health.system.ModuleDescriptor;
import vn.edu.uit.se214.health.system.ModuleRegistry;

/**
 * M4 – UC-06, UC-08, UC-09. History and feedback live here; answering (Safety Gate first, RAG,
 * LLM, output checks, SSE streaming) is delegated to the FastAPI service through PythonServiceClient.
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

  @RequiresSession
  @PostMapping(value = "/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter send(HttpServletRequest request, @RequestBody(required = false) SendMessageRequest body) {
    UUID userId = resolveUserId(request);
    return chatService.send(userId, body);
  }

  @RequiresSession
  @GetMapping("/sessions")
  public ApiResponse.Success<List<ChatSession>> sessions(HttpServletRequest request) {
    UUID userId = resolveUserId(request);
    List<ChatSession> list = chatService.listSessions(userId);
    return ApiResponse.of(list, new ApiResponse.Meta("ok", list.size()));
  }

  @RequiresSession
  @GetMapping("/sessions/{id}/messages")
  public ApiResponse.Success<List<ChatMessage>> messages(HttpServletRequest request, @PathVariable String id) {
    UUID userId = resolveUserId(request);
    UUID sessionId = parseUuid(id);
    List<ChatMessage> list = chatService.getMessages(sessionId, userId);
    return ApiResponse.of(list, new ApiResponse.Meta("ok", list.size()));
  }

  @RequiresSession
  @DeleteMapping("/sessions/{id}")
  public ApiResponse.Success<Void> deleteSession(HttpServletRequest request, @PathVariable String id) {
    UUID userId = resolveUserId(request);
    UUID sessionId = parseUuid(id);
    chatService.deleteSession(sessionId, userId);
    return ApiResponse.of(null);
  }

  @RequiresSession
  @PostMapping("/messages/{id}/feedback")
  public ApiResponse.Success<ChatFeedbackRecord> feedback(
      HttpServletRequest request, @PathVariable String id, @RequestBody(required = false) ChatFeedbackRequest body) {
    UUID userId = resolveUserId(request);
    UUID messageId = parseUuid(id);
    ChatFeedbackRecord record = chatService.submitFeedback(messageId, userId, body);
    return ApiResponse.of(record);
  }

  private UUID resolveUserId(HttpServletRequest request) {
    Object attr = request.getAttribute("userId");
    if (attr instanceof UUID uid) return uid;
    if (attr instanceof String str) {
      try {
        return UUID.fromString(str);
      } catch (Exception ignored) {
      }
    }
    return UUID.fromString("00000000-0000-4000-8000-000000000001");
  }

  private UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (Exception e) {
      throw ApiException.invalidRequest("ID không đúng định dạng UUID.");
    }
  }
}
