package vn.edu.uit.se214.health.safety;

import org.springframework.stereotype.Component;
import vn.edu.uit.se214.health.safety.api.SafetyDecision;
import vn.edu.uit.se214.health.safety.api.SafetyEvaluator;

/** No medical rules have been supplied or approved. This adapter MUST NOT return low risk. */
@Component
public class UnavailableSafetyEvaluator implements SafetyEvaluator {
  @Override
  public SafetyDecision evaluate(String text) {
    return SafetyDecision.unavailable();
  }
}
