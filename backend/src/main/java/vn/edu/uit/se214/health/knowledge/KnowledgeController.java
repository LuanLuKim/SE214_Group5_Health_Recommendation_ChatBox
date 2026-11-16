package vn.edu.uit.se214.health.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uit.se214.health.knowledge.api.ArticleSummary;
import vn.edu.uit.se214.health.knowledge.api.ArticleDetail;
import vn.edu.uit.se214.health.knowledge.api.FacilitySummary;
import vn.edu.uit.se214.health.knowledge.api.KnowledgeSearch;
import vn.edu.uit.se214.health.shared.web.ApiException;
import vn.edu.uit.se214.health.shared.web.ApiResponse;
import vn.edu.uit.se214.health.system.ModuleDescriptor;
import vn.edu.uit.se214.health.system.ModuleRegistry;

/**
 * M2 – UC-10, UC-11, UC-15, UC-17. Public search is open; authoring, the shared review queue
 * (UC-17, both the M1 and M2 parts) and feedback require authentication. Role checks remain pending.
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
  private static final long MAX_OFFSET = 9_007_199_254_740_991L; // JavaScript's safe integer limit.
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
    String query = textParam(request, "q", false);
    String topic = textParam(request, "topic", true);
    int limit = (int) pageParam(request, "limit", KnowledgeSearch.MAX_ARTICLE_PAGE_SIZE,
        1, KnowledgeSearch.MAX_ARTICLE_PAGE_SIZE);
    long offset = pageParam(request, "offset", 0, 0, MAX_OFFSET);
    KnowledgeSearch.ArticlePage page = search.articles(query, topic, limit, offset);
    return ApiResponse.of(page.items(),
        new ApiResponse.Meta(search.dataStatus(), page.total(), limit, offset,
            Math.ceilDiv(page.total(), limit)));
  }

  @GetMapping("/articles/{id}")
  public ApiResponse.Success<ArticleDetail> article(@PathVariable String id) {
    UUID articleId;
    try {
      articleId = UUID.fromString(id);
    } catch (IllegalArgumentException invalidId) {
      throw ApiException.notFound("Không tìm thấy bài viết.");
    }
    if (!articleId.toString().equalsIgnoreCase(id))
      throw ApiException.notFound("Không tìm thấy bài viết.");
    ArticleDetail detail = search.article(articleId)
        .orElseThrow(() -> ApiException.notFound("Không tìm thấy bài viết."));
    return ApiResponse.of(detail, new ApiResponse.Meta(search.dataStatus(), null));
  }

  @GetMapping("/facilities")
  public ApiResponse.Success<List<FacilitySummary>> facilities(HttpServletRequest request) {
    List<FacilitySummary> data = search.facilities(textParam(request, "q", false));
    return ApiResponse.of(data, new ApiResponse.Meta(search.dataStatus(), data.size()));
  }

  /**
   * Optional single text parameter, trimmed, at most 200 characters. Repetitions and control characters
   * are rejected (PostgreSQL refuses NUL in text, which would surface as a server error).
   */
  private static String textParam(HttpServletRequest request, String name, boolean nonBlank) {
    String[] values = request.getParameterValues(name);
    if (values == null) return "";
    String value = values.length == 1 ? values[0] : null;
    if (value == null || value.trim().length() > 200 || (nonBlank && value.isBlank())
        || value.chars().anyMatch(Character::isISOControl))
      throw ApiException.invalid("INVALID_QUERY", "Tham số " + name + " không hợp lệ.");
    return value.trim();
  }

  private static long pageParam(HttpServletRequest request, String name, long defaultValue,
      long min, long max) {
    String[] values = request.getParameterValues(name);
    if (values == null) return defaultValue;
    if (values.length == 1 && values[0].matches("0|[1-9][0-9]{0,15}")) {
      long number = Long.parseLong(values[0]);
      if (number >= min && number <= max) return number;
    }
    throw ApiException.invalid("INVALID_QUERY", "Tham số " + name + " không hợp lệ.");
  }

  @PostMapping("/articles")
  public Object createArticle(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @PatchMapping("/articles/{id}")
  public Object updateArticle(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @DeleteMapping("/articles/{id}")
  public Object deleteArticle(@PathVariable String id) {
    throw ApiException.notImplemented();
  }

  @GetMapping("/reviews")
  public Object reviewQueue() {
    throw ApiException.notImplemented();
  }

  @PostMapping("/reviews/{id}/decision")
  public Object decide(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @GetMapping("/feedback")
  public Object feedback() {
    throw ApiException.notImplemented();
  }

  @PatchMapping("/feedback/{id}")
  public Object handleFeedback(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }
}
