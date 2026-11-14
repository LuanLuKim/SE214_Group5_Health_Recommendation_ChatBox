package vn.edu.uit.se214.health.safety;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.uit.se214.health.shared.domain.ReviewStatus;
import vn.edu.uit.se214.health.shared.web.ApiException;
import vn.edu.uit.se214.health.shared.web.ApiResponse;
import vn.edu.uit.se214.health.system.ModuleDescriptor;
import vn.edu.uit.se214.health.system.ModuleRegistry;

/**
 * M1 – UC-07, UC-16. Rule authoring routes are wired to {@link RuleDraftService} and require
 * authentication (SpringSecurityConfiguration); the CONTENT_EXPERT role check still belongs here
 * before any service call. Nothing in these routes approves or activates a rule.
 */
@RestController
@RequestMapping("/api/v1/safety")
public class SafetyController {
  private final Optional<RuleDraftService> drafts;
  private final ObjectMapper strictJson;

  public SafetyController(Optional<RuleDraftService> drafts, ObjectMapper json) {
    this.drafts = drafts;
    // Same as z.strictObject in packages/contracts: unknown fields are rejected, not ignored.
    this.strictJson = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }

  @GetMapping
  public ApiResponse.Success<ModuleDescriptor> module() {
    return ApiResponse.of(ModuleRegistry.get("safety"));
  }

  @PostMapping("/evaluate")
  public Object evaluate(@RequestBody(required = false) JsonNode body) {
    throw ApiException.notImplemented();
  }

  @GetMapping("/rules")
  public ApiResponse.Success<List<SafetyRule>> listRules() {
    List<SafetyRule> rules = service().list();
    return ApiResponse.of(rules, new ApiResponse.Meta(null, rules.size()));
  }

  @GetMapping("/rules/{id}")
  public ApiResponse.Success<SafetyRule> getRule(@PathVariable String id) {
    return ApiResponse.of(service().get(ruleId(id)).orElseThrow(SafetyController::notFound));
  }

  @PostMapping("/rules")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse.Success<SafetyRule> createRule(@RequestBody(required = false) JsonNode body) {
    RuleDraftService service = service();
    return ApiResponse.of(service.create(draft(body)));
  }

  @PatchMapping("/rules/{id}")
  public ApiResponse.Success<SafetyRule> updateRule(
      @PathVariable String id, @RequestBody(required = false) JsonNode body) {
    RuleDraftService service = service();
    UUID ruleId = ruleId(id);
    RuleDraftInput input = draft(body);
    return ApiResponse.of(service.update(ruleId, input).orElseThrow(() -> notEditable(service, ruleId)));
  }

  /** UC-16: hand a sourced draft to the shared UC-17 review queue. It stays inert until approved. */
  @PostMapping("/rules/{id}/submit")
  public ApiResponse.Success<SafetyRule> submitRule(@PathVariable String id) {
    RuleDraftService service = service();
    UUID ruleId = ruleId(id);
    return ApiResponse.of(service.submit(ruleId).orElseThrow(() -> notEditable(service, ruleId)));
  }

  private RuleDraftService service() {
    return drafts.orElseThrow(
        () ->
            new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "RULES_UNAVAILABLE",
                "Kho quy tắc cảnh báo chưa được cấu hình."));
  }

  private RuleDraftInput draft(JsonNode body) {
    if (body == null || !body.isObject()) throw invalidDraft();
    try {
      return strictJson.treeToValue(body, RuleDraftInput.class);
    } catch (Exception e) {
      // Unknown fields, wrong types or a care level outside the five allowed values.
      throw invalidDraft();
    }
  }

  private static UUID ruleId(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw notFound();
    }
  }

  /** An empty result means the rule is missing, or it is pending/approved and therefore locked. */
  private static ApiException notEditable(RuleDraftService service, UUID id) {
    Optional<SafetyRule> rule = service.get(id);
    if (rule.isEmpty()) return notFound();
    ReviewStatus status = rule.get().reviewStatus();
    if (status == ReviewStatus.DRAFT || status == ReviewStatus.RETURNED) {
      // Changed between read and write; the client should reload and retry.
      return new ApiException(
          HttpStatus.CONFLICT, "RULE_CHANGED", "Quy tắc vừa được thay đổi. Vui lòng tải lại.");
    }
    return new ApiException(
        HttpStatus.CONFLICT, "RULE_NOT_EDITABLE", "Quy tắc đang chờ duyệt hoặc đã duyệt, không thể sửa hay gửi lại.");
  }

  private static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "RULE_NOT_FOUND", "Không tìm thấy quy tắc.");
  }

  private static ApiException invalidDraft() {
    return ApiException.invalid("INVALID_RULE_DRAFT", "Bản nháp quy tắc không hợp lệ.");
  }
}
