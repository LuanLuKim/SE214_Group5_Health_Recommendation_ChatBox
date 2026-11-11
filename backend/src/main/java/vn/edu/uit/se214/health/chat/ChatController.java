package vn.edu.uit.se214.health.chat;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uit.se214.health.shared.web.ApiException;
import vn.edu.uit.se214.health.shared.web.ApiResponse;
import vn.edu.uit.se214.health.shared.web.RequiresSession;
import vn.edu.uit.se214.health.system.ModuleDescriptor;
import vn.edu.uit.se214.health.system.ModuleRegistry;

/**
 * M4 – UC-06, UC-08, UC-09. History and feedback live here; answering (Safety Gate first, RAG,
 * LLM, output checks, SSE streaming) is delegated to the FastAPI service through AiServiceClient.
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
  @GetMapping
  public ApiResponse.Success<ModuleDescriptor> module() {
    return ApiResponse.of(ModuleRegistry.get("chat"));
  }

  @RequiresSession
  @PostMapping("/messages")
  public Object send(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @GetMapping("/sessions")
  public Object sessions() {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @GetMapping("/sessions/{id}/messages")
  public Object messages(@PathVariable String id) {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @DeleteMapping("/sessions/{id}")
  public Object deleteSession(@PathVariable String id) {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @PostMapping("/messages/{id}/feedback")
  public Object feedback(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }
}
