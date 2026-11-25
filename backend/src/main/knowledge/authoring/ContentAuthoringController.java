package knowledge.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import shared.web.ApiException;
import shared.web.ApiResponse;
import shared.web.CurrentUser;

/**
 * M2 – UC-15 Quản lý bài viết và mẫu kế hoạch. Every route needs PERM_CONTENT_MANAGE (CONTENT_EXPERT, ADMIN),
 * checked by SpringSecurityConfiguration before this controller runs; the editor is the access token's user.
 * The private lists and details are under {@code /authoring}, apart from the public article search.
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class ContentAuthoringController {
  private final Optional<ArticleDrafts> articles;
  private final Optional<TemplateDrafts> templates;

  public ContentAuthoringController(Optional<ArticleDrafts> articles, Optional<TemplateDrafts> templates) {
    this.articles = articles;
    this.templates = templates;
  }

  // -- articles ------------------------------------------------------------

  @GetMapping("/authoring/articles")
  public ApiResponse.Success<List<ArticleDraft>> listArticles() {
    List<ArticleDraft> data = available(articles).list();
    return ApiResponse.of(data, new ApiResponse.Meta(null, data.size()));
  }

  @GetMapping("/authoring/articles/{id}")
  public ApiResponse.Success<ArticleDraft> getArticle(@PathVariable String id) {
    ArticleDrafts drafts = available(articles);
    return ApiResponse.of(drafts.get(id(id, "Không tìm thấy bài viết.")));
  }

  @PostMapping("/articles")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse.Success<ArticleDraft> createArticle(
      @AuthenticationPrincipal Jwt principal, @RequestBody(required = false) JsonNode body) {
    return ApiResponse.of(available(articles).create(body, CurrentUser.id(principal)));
  }

  @PatchMapping("/articles/{id}")
  public ApiResponse.Success<ArticleDraft> updateArticle(@AuthenticationPrincipal Jwt principal,
      @PathVariable String id, @RequestBody(required = false) JsonNode body) {
    ArticleDrafts drafts = available(articles);
    return ApiResponse.of(drafts.update(id(id, "Không tìm thấy bài viết."), body, CurrentUser.id(principal)));
  }

  @DeleteMapping("/articles/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteArticle(@PathVariable String id) {
    ArticleDrafts drafts = available(articles);
    drafts.delete(id(id, "Không tìm thấy bài viết."));
  }

  @PostMapping("/articles/{id}/submit")
  public ApiResponse.Success<ArticleDraft> submitArticle(@AuthenticationPrincipal Jwt principal,
      @PathVariable String id, @RequestBody(required = false) JsonNode body) {
    ArticleDrafts drafts = available(articles);
    return ApiResponse.of(drafts.submit(id(id, "Không tìm thấy bài viết."), body, CurrentUser.id(principal)));
  }

  // -- plan templates --------------------------------------------------------

  @GetMapping("/authoring/templates")
  public ApiResponse.Success<List<TemplateDraft>> listTemplates() {
    List<TemplateDraft> data = available(templates).list();
    return ApiResponse.of(data, new ApiResponse.Meta(null, data.size()));
  }

  @GetMapping("/authoring/templates/{id}")
  public ApiResponse.Success<TemplateDraft> getTemplate(@PathVariable String id) {
    TemplateDrafts drafts = available(templates);
    return ApiResponse.of(drafts.get(id(id, "Không tìm thấy mẫu kế hoạch.")));
  }

  @PostMapping("/templates")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse.Success<TemplateDraft> createTemplate(
      @AuthenticationPrincipal Jwt principal, @RequestBody(required = false) JsonNode body) {
    return ApiResponse.of(available(templates).create(body, CurrentUser.id(principal)));
  }

  @PatchMapping("/templates/{id}")
  public ApiResponse.Success<TemplateDraft> updateTemplate(@AuthenticationPrincipal Jwt principal,
      @PathVariable String id, @RequestBody(required = false) JsonNode body) {
    TemplateDrafts drafts = available(templates);
    return ApiResponse.of(drafts.update(id(id, "Không tìm thấy mẫu kế hoạch."), body, CurrentUser.id(principal)));
  }

  @DeleteMapping("/templates/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteTemplate(@PathVariable String id) {
    TemplateDrafts drafts = available(templates);
    drafts.delete(id(id, "Không tìm thấy mẫu kế hoạch."));
  }

  @PostMapping("/templates/{id}/submit")
  public ApiResponse.Success<TemplateDraft> submitTemplate(@AuthenticationPrincipal Jwt principal,
      @PathVariable String id, @RequestBody(required = false) JsonNode body) {
    TemplateDrafts drafts = available(templates);
    return ApiResponse.of(drafts.submit(id(id, "Không tìm thấy mẫu kế hoạch."), body, CurrentUser.id(principal)));
  }

  private static <S> S available(Optional<S> service) {
    return service.orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CONTENT_UNAVAILABLE",
        "Kho nội dung chưa được cấu hình."));
  }

  /** A canonical UUID; anything else is the same 404 as an unknown id. */
  private static UUID id(String value, String notFound) {
    try {
      UUID id = UUID.fromString(value);
      if (id.toString().equalsIgnoreCase(value)) return id;
    } catch (IllegalArgumentException e) {
      // same 404 below
    }
    throw ApiException.notFound(notFound);
  }
}
