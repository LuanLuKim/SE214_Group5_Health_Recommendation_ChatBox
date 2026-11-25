package safety;

import org.springframework.stereotype.Component;
import safety.api.SafetyDecision;
import safety.api.SafetyEvaluator;

/** No medical rules have been supplied or approved. This adapter MUST NOT return low risk. */
@Component
public class UnavailableSafetyEvaluator implements SafetyEvaluator {
  @Override
  public SafetyDecision evaluate(String text) {
    return SafetyDecision.unavailable();
  }
}
