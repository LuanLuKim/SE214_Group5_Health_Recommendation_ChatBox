package vn.edu.uit.se214.health.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.edu.uit.se214.health.shared.domain.ReviewStatus;
import vn.edu.uit.se214.health.shared.web.ApiException;
import vn.edu.uit.se214.health.support.TestDatabase;

/**
 * UC-16 routes called directly: over HTTP they require authentication (see ApiTest).
 */
class SafetyControllerTest {
  final ObjectMapper json = new ObjectMapper();
  JdbcClient jdbc;
  SafetyController controller;

  @BeforeEach
  void freshDatabase() {
    jdbc = JdbcClient.create(TestDatabase.fresh());
    RuleDraftService service = new RuleDraftService(new RuleRepository(jdbc, json));
    controller = new SafetyController(Optional.of(service), json);
  }

  ObjectNode body() {
    return json.valueToTree(RuleDraftServiceTest.DRAFT);
  }

  static void fails(ThrowingCallable call, HttpStatus status, String code) {
    Consumer<Throwable> check =
        e -> {
          assertThat(((ApiException) e).status()).isEqualTo(status);
          assertThat(((ApiException) e).code()).isEqualTo(code);
        };
    assertThatThrownBy(call).isInstanceOf(ApiException.class).satisfies(check::accept);
  }

  @Test
  void draftLifecycleCreateListReadEditSubmit() {
    SafetyRule created = controller.createRule(body()).data();
    assertThat(created.reviewStatus()).isEqualTo(ReviewStatus.DRAFT);
    assertThat(controller.listRules().meta().total()).isEqualTo(1);
    assertThat(controller.getRule(created.id()).data()).isEqualTo(created);

    ObjectNode edited = body().put("name", "[TEST] Đã sửa");
    assertThat(controller.updateRule(created.id(), edited).data().name()).isEqualTo("[TEST] Đã sửa");

    SafetyRule pending = controller.submitRule(created.id()).data();
    assertThat(pending.reviewStatus()).isEqualTo(ReviewStatus.PENDING_REVIEW);

    // Pending rules are locked: no edit and no second submission.
    fails(() -> controller.updateRule(created.id(), body()), HttpStatus.CONFLICT, "RULE_NOT_EDITABLE");
    fails(() -> controller.submitRule(created.id()), HttpStatus.CONFLICT, "RULE_NOT_EDITABLE");
  }

  @Test
  void onlyTheFiveCareLevelsAndKnownFieldsAreAccepted() {
    fails(() -> controller.createRule(body().put("careLevel", "LOW")), HttpStatus.BAD_REQUEST, "INVALID_RULE_DRAFT");
    fails(() -> controller.createRule(body().put("reviewStatus", "APPROVED")), HttpStatus.BAD_REQUEST, "INVALID_RULE_DRAFT");
    fails(() -> controller.createRule(body().putNull("careLevel")), HttpStatus.BAD_REQUEST, "INVALID_RULE_DRAFT");
    fails(() -> controller.createRule(null), HttpStatus.BAD_REQUEST, "INVALID_RULE_DRAFT");
    fails(() -> controller.createRule(json.createArrayNode()), HttpStatus.BAD_REQUEST, "INVALID_RULE_DRAFT");
    assertThat(controller.listRules().data()).isEmpty();

    for (String level :
        new String[] {"EMERGENCY_NOW", "SEEK_CARE_SOON", "ROUTINE_CONSULT", "GENERAL_SELF_CARE_INFO", "INSUFFICIENT_INFO"})
      assertThat(controller.createRule(body().put("careLevel", level)).data().careLevel().name()).isEqualTo(level);
  }

  @Test
  void submitWithoutSourceIsRejectedAndRuleStaysDraft() {
    String id = controller.createRule(body()).data().id();
    jdbc.sql("UPDATE safety.rules SET source_name = '' WHERE id = ?").param(UUID.fromString(id)).update();

    fails(() -> controller.submitRule(id), HttpStatus.BAD_REQUEST, "RULE_SOURCE_REQUIRED");
    assertThat(controller.getRule(id).data().reviewStatus()).isEqualTo(ReviewStatus.DRAFT);
  }

  @Test
  void missingOrMalformedIdsAreNotFound() {
    String unknown = UUID.randomUUID().toString();
    fails(() -> controller.getRule(unknown), HttpStatus.NOT_FOUND, "RULE_NOT_FOUND");
    fails(() -> controller.getRule("not-a-uuid"), HttpStatus.NOT_FOUND, "RULE_NOT_FOUND");
    fails(() -> controller.updateRule(unknown, body()), HttpStatus.NOT_FOUND, "RULE_NOT_FOUND");
    fails(() -> controller.submitRule(unknown), HttpStatus.NOT_FOUND, "RULE_NOT_FOUND");
  }

  @Test
  void withoutDatabaseTheRoutesReportUnavailable() {
    SafetyController mock = new SafetyController(Optional.empty(), json);
    fails(mock::listRules, HttpStatus.SERVICE_UNAVAILABLE, "RULES_UNAVAILABLE");
    fails(() -> mock.createRule(body()), HttpStatus.SERVICE_UNAVAILABLE, "RULES_UNAVAILABLE");
  }
}
