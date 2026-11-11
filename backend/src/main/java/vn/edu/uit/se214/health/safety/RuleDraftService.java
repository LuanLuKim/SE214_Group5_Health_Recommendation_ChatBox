package vn.edu.uit.se214.health.safety;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** UC-16: create, list, read and edit rule drafts. Nothing here activates a rule. */
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
}
