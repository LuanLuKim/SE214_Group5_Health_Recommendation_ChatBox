package vn.edu.uit.se214.health.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import vn.edu.uit.se214.health.integrations.PythonServiceClient;
import vn.edu.uit.se214.health.safety.api.CareLevel;
import vn.edu.uit.se214.health.safety.api.SafetyDecision;
import vn.edu.uit.se214.health.safety.api.SafetyEvaluator;

/** Sends approved rule versions and explicit facts to the deterministic FastAPI Safety Gate. */
public class RuleBasedSafetyEvaluator implements SafetyEvaluator {
  private final RuleRepository repository;
  private final PythonServiceClient ai;
  private final ObjectMapper json;

  public RuleBasedSafetyEvaluator(RuleRepository repository, PythonServiceClient ai, ObjectMapper json) {
    this.repository = repository;
    this.ai = ai;
    this.json = json;
  }

  @Override
  public SafetyDecision evaluate(String text) {
    // Text alone is not evidence that a symptom is present or absent.
    return SafetyDecision.unavailable();
  }

  @Override
  public SafetyDecision evaluateFacts(String normalizedText, Set<String> observedFacts) {
    if (normalizedText == null || normalizedText.isBlank() || observedFacts == null || observedFacts.isEmpty()) {
      return SafetyDecision.unavailable();
    }
    try {
      List<ApprovedSafetyRule> rules = repository.listEvaluable();
      if (rules.isEmpty() || !ai.configured()) return SafetyDecision.unavailable();
      JsonNode response =
          ai.post(
              "/v1/safety/evaluate",
              Map.of("facts", observedFacts, "rules", rules),
              JsonNode.class);
      if (response == null || !response.hasNonNull("data")) return SafetyDecision.unavailable();
      SafetyDecision decision = json.treeToValue(response.get("data"), SafetyDecision.class);
      if (decision.status() == SafetyDecision.Status.UNAVAILABLE) return SafetyDecision.unavailable();
      if (decision.status() != SafetyDecision.Status.EVALUATED || decision.careLevel() == null) {
        return SafetyDecision.unavailable();
      }
      if (decision.matchedRuleIds() == null
          || decision.missingFields() == null
          || decision.specialtyIds() == null
          || decision.sources() == null) {
        return SafetyDecision.unavailable();
      }
      Map<String, ApprovedSafetyRule> byId =
          rules.stream().collect(Collectors.toMap(ApprovedSafetyRule::id, rule -> rule));
      if (!byId.keySet().containsAll(decision.matchedRuleIds())) return SafetyDecision.unavailable();
      if (decision.matchedRuleIds().isEmpty()) {
        Set<String> allowedFields =
            rules.stream().flatMap(rule -> rule.requiredFields().stream()).collect(Collectors.toSet());
        if (decision.careLevel() != CareLevel.INSUFFICIENT_INFO
            || decision.missingFields().isEmpty()
            || !allowedFields.containsAll(decision.missingFields())
            || !decision.specialtyIds().isEmpty()
            || !decision.sources().isEmpty()) {
          return SafetyDecision.unavailable();
        }
      } else {
        List<ApprovedSafetyRule> matched =
            decision.matchedRuleIds().stream().map(byId::get).toList();
        if (matched.stream().anyMatch(rule -> rule.careLevel() != decision.careLevel())
            || !decision.missingFields().isEmpty()) {
          return SafetyDecision.unavailable();
        }
        Set<String> allowedSpecialties =
            matched.stream().flatMap(rule -> rule.specialtyIds().stream()).collect(Collectors.toSet());
        if (!allowedSpecialties.containsAll(decision.specialtyIds())) return SafetyDecision.unavailable();
        Set<vn.edu.uit.se214.health.shared.domain.SourceReference> allowedSources =
            matched.stream().map(ApprovedSafetyRule::source).collect(Collectors.toSet());
        if (!allowedSources.containsAll(decision.sources())) return SafetyDecision.unavailable();
      }
      return decision;
    } catch (Exception ignored) {
      // Store or service failures must never become a low-risk care direction.
      return SafetyDecision.unavailable();
    }
  }
}
