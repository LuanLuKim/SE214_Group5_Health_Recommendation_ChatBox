package vn.edu.uit.se214.health.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vn.edu.uit.se214.health.integrations.PythonServiceClient;
import vn.edu.uit.se214.health.safety.api.CareLevel;
import vn.edu.uit.se214.health.safety.api.SafetyDecision;
import vn.edu.uit.se214.health.shared.AppProperties;
import vn.edu.uit.se214.health.shared.domain.SourceReference;

class RuleBasedSafetyEvaluatorTest {
  @Test
  void sendsOnlyApprovedRulePayloadAndExplicitFactsToTheAiService() {
    RuleRepository repository = mock(RuleRepository.class);
    when(repository.listEvaluable()).thenReturn(List.of(approvedRule()));
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    var ai =
        new PythonServiceClient(
            new AppProperties.PythonService("http://ai.internal:8001", "synthetic-key", 1000), builder);
    server
        .expect(requestTo("http://ai.internal:8001/v1/safety/evaluate"))
        .andExpect(content().json("{\"facts\":[\"synthetic_sign\"],\"rules\":[{\"id\":\"rule-1\",\"reviewStatus\":\"APPROVED\",\"active\":true}]}"))
        .andExpect(content().string(not(containsString("\"text\""))))
        .andRespond(
            withSuccess(
                "{\"data\":{\"status\":\"EVALUATED\",\"careLevel\":\"SEEK_CARE_SOON\",\"matchedRuleIds\":[\"rule-1\"],\"missingFields\":[],\"specialtyIds\":[],\"sources\":[]}}",
                MediaType.APPLICATION_JSON));

    SafetyDecision decision =
        new RuleBasedSafetyEvaluator(repository, ai, new ObjectMapper())
            .evaluateFacts("[TEST] normalized", Set.of("synthetic_sign"));
    assertThat(decision.careLevel()).isEqualTo(CareLevel.SEEK_CARE_SOON);
    assertThat(decision.matchedRuleIds()).containsExactly("rule-1");
    server.verify();
  }

  @Test
  void missingRulesAndStoreOrServiceErrorsFailClosed() {
    RuleRepository repository = mock(RuleRepository.class);
    var ai =
        new PythonServiceClient(
            new AppProperties.PythonService("http://ai.internal:8001", "", 1000), RestClient.builder());
    var evaluator = new RuleBasedSafetyEvaluator(repository, ai, new ObjectMapper());
    when(repository.listEvaluable()).thenReturn(List.of());
    assertThat(evaluator.evaluateFacts("[TEST] normalized", Set.of("synthetic_sign")).status())
        .isEqualTo(SafetyDecision.Status.UNAVAILABLE);
    when(repository.listEvaluable()).thenReturn(List.of(approvedRule()));
    assertThat(evaluator.evaluateFacts("[TEST] normalized", Set.of("synthetic_sign")).status())
        .isEqualTo(SafetyDecision.Status.UNAVAILABLE);
    when(repository.listEvaluable()).thenThrow(new IllegalStateException("store offline"));
    assertThat(evaluator.evaluateFacts("[TEST] normalized", Set.of("synthetic_sign")).status())
        .isEqualTo(SafetyDecision.Status.UNAVAILABLE);
  }

  @Test
  void rejectsServiceDecisionThatWeakensAnApprovedRule() {
    RuleRepository repository = mock(RuleRepository.class);
    when(repository.listEvaluable()).thenReturn(List.of(approvedRule()));
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    var ai =
        new PythonServiceClient(
            new AppProperties.PythonService("http://ai.internal:8001", "synthetic-key", 1000), builder);
    server
        .expect(requestTo("http://ai.internal:8001/v1/safety/evaluate"))
        .andRespond(
            withSuccess(
                "{\"data\":{\"status\":\"EVALUATED\",\"careLevel\":\"GENERAL_SELF_CARE_INFO\",\"matchedRuleIds\":[\"rule-1\"],\"missingFields\":[],\"specialtyIds\":[],\"sources\":[]}}",
                MediaType.APPLICATION_JSON));
    SafetyDecision decision =
        new RuleBasedSafetyEvaluator(repository, ai, new ObjectMapper())
            .evaluateFacts("[TEST] normalized", Set.of("synthetic_sign"));
    assertThat(decision.status()).isEqualTo(SafetyDecision.Status.UNAVAILABLE);
    server.verify();
  }

  private static ApprovedSafetyRule approvedRule() {
    return new ApprovedSafetyRule(
        "rule-1",
        new RuleDraftInput.Conditions(List.of("synthetic_sign"), List.of()),
        List.of(),
        CareLevel.SEEK_CARE_SOON,
        List.of(),
        new SourceReference("rule-1", "[TEST] source", "https://example.invalid/rule", "2026-11-11T00:00:00Z"),
        "APPROVED",
        true);
  }
}
