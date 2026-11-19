package vn.edu.uit.se214.health.safety;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import vn.edu.uit.se214.health.safety.api.CareLevel;
import vn.edu.uit.se214.health.shared.domain.ReviewStatus;
import vn.edu.uit.se214.health.support.TestDatabase;

/**
 * UC-16 DoD: only approved rules are loaded. The loader itself is UC-07's
 * {@link RuleRepository#listEvaluable()} (see ApprovedRuleRepositoryTest); this test proves that
 * nothing done through UC-16 authoring can put a rule into that set.
 */
class ApprovedRulesTest {
  static final String PREFIX = "00000000-0000-4000-8000-000000000";
  static final List<String> EVALUABLE =
      List.of(PREFIX + "300", PREFIX + "301", PREFIX + "302", PREFIX + "303", PREFIX + "304");

  JdbcClient jdbc;
  RuleRepository repository;
  RuleDraftService service;

  @BeforeEach
  void freshDatabaseWithSampleRules() {
    DataSource dataSource = TestDatabase.fresh();
    var populator = new ResourceDatabasePopulator(new ClassPathResource("safety/sample-rules.sql"));
    populator.setSqlScriptEncoding("UTF-8");
    populator.execute(dataSource);
    jdbc = JdbcClient.create(dataSource);
    repository = new RuleRepository(jdbc, new ObjectMapper());
    service = new RuleDraftService(repository);
  }

  List<String> evaluableIds() {
    return repository.listEvaluable().stream().map(ApprovedSafetyRule::id).toList();
  }

  @Test
  void fixtureCoversAllFiveCareLevelsAndAllReviewStates() {
    List<SafetyRule> all = repository.list();
    assertThat(all).hasSize(15);
    assertThat(all).extracting(SafetyRule::careLevel).contains(CareLevel.values());
    assertThat(all).extracting(SafetyRule::reviewStatus).contains(ReviewStatus.values());
  }

  @Test
  void onlyTheApprovedActiveRulesAreLoaded() {
    List<ApprovedSafetyRule> loaded = repository.listEvaluable();
    assertThat(loaded).extracting(ApprovedSafetyRule::id).containsExactlyElementsOf(EVALUABLE);
    assertThat(loaded).extracting(ApprovedSafetyRule::careLevel).containsExactlyInAnyOrder(CareLevel.values());
    assertThat(loaded.get(1).conditions().any()).hasSize(2);
    assertThat(loaded.get(4).requiredFields()).containsExactly("__SYNTHETIC_ONLY__onset");
  }

  @Test
  void malformedApprovedRulesAreSkippedSoTheyCannotDisableTheWholeGate() {
    // 311-314 are approved, active and verified, but services/ai would reject them.
    assertThat(evaluableIds())
        .doesNotContain(PREFIX + "311", PREFIX + "312", PREFIX + "313", PREFIX + "314")
        .containsExactlyElementsOf(EVALUABLE);
    assertThat(repository.listEvaluable())
        .allSatisfy(
            rule -> {
              assertThat(rule.conditions().all()).isNotEmpty().hasSizeLessThanOrEqualTo(20);
              assertThat(rule.conditions().any()).isNotNull().hasSizeLessThanOrEqualTo(20);
            });
  }

  @Test
  void creatingEditingAndSubmittingThroughUc16NeverMakesARuleLoadable() {
    SafetyRule created = service.create(RuleDraftServiceTest.DRAFT);
    UUID id = UUID.fromString(created.id());
    assertThat(evaluableIds()).doesNotContain(created.id());

    service.update(id, RuleDraftServiceTest.DRAFT).orElseThrow();
    assertThat(evaluableIds()).doesNotContain(created.id());

    assertThat(service.submit(id)).get().extracting(SafetyRule::reviewStatus).isEqualTo(ReviewStatus.PENDING_REVIEW);
    assertThat(evaluableIds()).containsExactlyElementsOf(EVALUABLE);

    // Fixture drafts and returned rules: submitting them is not approval either.
    service.submit(UUID.fromString(PREFIX + "305")).orElseThrow();
    service.submit(UUID.fromString(PREFIX + "307")).orElseThrow();
    assertThat(evaluableIds()).containsExactlyElementsOf(EVALUABLE);
  }

  @Test
  void uc16CannotEditOrResubmitALoadedRule() {
    UUID loaded = UUID.fromString(PREFIX + "300");
    assertThat(service.update(loaded, RuleDraftServiceTest.DRAFT)).isEmpty();
    assertThat(service.submit(loaded)).isEmpty();
    assertThat(repository.listEvaluable().getFirst().conditions().all()).containsExactly("__SYNTHETIC_ONLY__emergency");
  }

  @Test
  void retiringAnApprovedRuleRemovesItFromTheLoadedSet() {
    jdbc.sql("UPDATE safety.rules SET review_status = 'RETIRED', is_active = false WHERE id = ?")
        .param(UUID.fromString(PREFIX + "300"))
        .update();
    assertThat(evaluableIds()).doesNotContain(PREFIX + "300").hasSize(4);
  }
}
