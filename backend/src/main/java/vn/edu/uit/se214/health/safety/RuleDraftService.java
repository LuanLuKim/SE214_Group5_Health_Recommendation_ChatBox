package vn.edu.uit.se214.health.safety;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.edu.uit.se214.health.shared.domain.ReviewStatus;
import vn.edu.uit.se214.health.shared.web.ApiException;

/** UC-16: create, list, read, edit and submit rule drafts. Nothing here activates a rule. */
public class RuleDraftService {
  private final RuleRepository repository;

  public RuleDraftService(RuleRepository repository) {
    this.repository = repository;
  }

  public SafetyRule create(RuleDraftInput input) {
    return repository.create(input.validated());
  }

  public List<SafetyRule> list() {
    return repository.list();
  }

  public Optional<SafetyRule> get(UUID id) {
    return repository.get(id);
  }

  public Optional<SafetyRule> update(UUID id, RuleDraftInput input) {
    return repository.update(id, input.validated());
  }

  /** Mark an editable rule pending for the shared UC-17 review flow; this never approves it. */
  public Optional<SafetyRule> submit(UUID id) {
    Optional<SafetyRule> existing = repository.get(id);
    if (existing.isEmpty()) return Optional.empty();
    SafetyRule rule = existing.get();
    if (rule.reviewStatus() != ReviewStatus.DRAFT && rule.reviewStatus() != ReviewStatus.RETURNED) {
      return Optional.empty();
    }
    if (rule.sourceName() == null
        || rule.sourceName().isBlank()
        || !RuleDraftInput.isHttpUrlWithoutCredentials(rule.sourceUrl())) {
      throw ApiException.invalid("RULE_SOURCE_REQUIRED", "Quy tắc cần có nguồn hợp lệ trước khi gửi duyệt.");
    }
    return repository.submit(id, rule.sourceName(), rule.sourceUrl());
  }
}
