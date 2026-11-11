package vn.edu.uit.se214.health.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uit.se214.health.knowledge.api.ArticleSummary;
import vn.edu.uit.se214.health.knowledge.api.FacilitySummary;
import vn.edu.uit.se214.health.knowledge.api.KnowledgeSearch;
import vn.edu.uit.se214.health.shared.web.ApiException;
import vn.edu.uit.se214.health.shared.web.ApiResponse;
import vn.edu.uit.se214.health.shared.web.RequiresSession;
import vn.edu.uit.se214.health.system.ModuleDescriptor;
import vn.edu.uit.se214.health.system.ModuleRegistry;

/**
 * M2 – UC-10, UC-11, UC-15, UC-17. Public search is open; authoring, the shared review queue
 * (UC-17, both the M1 and M2 parts) and feedback stay closed until M5 provides expert sessions.
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
  private final KnowledgeSearch search;

  public KnowledgeController(KnowledgeSearch search) {
    this.search = search;
  }

  @GetMapping
  public ApiResponse.Success<ModuleDescriptor> module() {
    return ApiResponse.of(ModuleRegistry.get("knowledge"));
  }

  @GetMapping("/articles")
  public ApiResponse.Success<List<ArticleSummary>> articles(HttpServletRequest request) {
    List<ArticleSummary> data = search.articles(query(request));
    return ApiResponse.of(data, new ApiResponse.Meta(search.dataStatus(), data.size()));
  }

  @GetMapping("/facilities")
  public ApiResponse.Success<List<FacilitySummary>> facilities(HttpServletRequest request) {
    List<FacilitySummary> data = search.facilities(query(request));
    return ApiResponse.of(data, new ApiResponse.Meta(search.dataStatus(), data.size()));
  }

  /** Optional single "q", trimmed, at most 200 characters. Repeated "q" is rejected. */
  private static String query(HttpServletRequest request) {
    String[] values = request.getParameterValues("q");
    if (values == null) return "";
    if (values.length != 1 || values[0].trim().length() > 200)
      throw ApiException.invalid("INVALID_QUERY", "Từ khóa phải là văn bản tối đa 200 ký tự.");
    return values[0].trim();
  }

  @RequiresSession
  @PostMapping("/articles")
  public Object createArticle(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @PatchMapping("/articles/{id}")
  public Object updateArticle(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @DeleteMapping("/articles/{id}")
  public Object deleteArticle(@PathVariable String id) {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @GetMapping("/reviews")
  public Object reviewQueue() {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @PostMapping("/reviews/{id}/decision")
  public Object decide(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @GetMapping("/feedback")
  public Object feedback() {
    throw ApiException.notImplemented();
  }

  @RequiresSession
  @PatchMapping("/feedback/{id}")
  public Object handleFeedback(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }
}
