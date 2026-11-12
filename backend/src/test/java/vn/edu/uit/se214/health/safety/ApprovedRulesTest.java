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

/** UC-16 DoD: only approved rules are loadable; the test-only fixture covers every level and state. */
class ApprovedRulesTest {
  static final String PREFIX = "00000000-0000-4000-8000-000000000";

  JdbcClient jdbc;
  RuleRepository repository;

  @BeforeEach
  void freshDatabaseWithSampleRules() {
    DataSource dataSource = TestDatabase.fresh();
    var populator = new ResourceDatabasePopulator(new ClassPathResource("safety/sample-rules.sql"));
    populator.setSqlScriptEncoding("UTF-8");
    populator.execute(dataSource);
    jdbc = JdbcClient.create(dataSource);
    repository = new RuleRepository(jdbc, new ObjectMapper());
  }

  @Test
  void fixtureCoversAllFiveCareLevelsAndAllReviewStates() {
    List<SafetyRule> all = repository.list();
    assertThat(all).hasSize(11).allMatch(SafetyRule::isSynthetic);
    assertThat(all).extracting(SafetyRule::careLevel).contains(CareLevel.values());
    assertThat(all).extracting(SafetyRule::reviewStatus).contains(ReviewStatus.values());
  }

  @Test
  void onlyApprovedRulesWithConditionsAreLoaded() {
    List<SafetyRule> approved = repository.listApproved();

    assertThat(approved)
        .extracting(SafetyRule::id)
        .containsExactly(PREFIX + "300", PREFIX + "301", PREFIX + "302", PREFIX + "303", PREFIX + "304");
    assertThat(approved).allMatch(rule -> rule.reviewStatus() == ReviewStatus.APPROVED);
    assertThat(approved).allMatch(rule -> !rule.conditions().all().isEmpty());
    assertThat(approved).extracting(SafetyRule::careLevel).containsExactlyInAnyOrder(CareLevel.values());
    assertThat(approved.get(1).conditions().any()).hasSize(2);
    assertThat(approved.get(4).requiredFields()).containsExactly("__SYNTHETIC_ONLY__onset");
  }

  @Test
  void submittingAPendingDraftDoesNotMakeItLoadable() {
    RuleDraftService service = new RuleDraftService(repository);
    UUID draft = UUID.fromString(PREFIX + "305");

    assertThat(service.submit(draft)).get().extracting(SafetyRule::reviewStatus).isEqualTo(ReviewStatus.PENDING_REVIEW);
    assertThat(repository.listApproved()).extracting(SafetyRule::id).doesNotContain(PREFIX + "305", PREFIX + "306");
  }

  @Test
  void retiringAnApprovedRuleRemovesItFromTheLoadedSet() {
    jdbc.sql("UPDATE safety.rules SET review_status = 'RETIRED' WHERE id = ?")
        .param(UUID.fromString(PREFIX + "300"))
        .update();
    assertThat(repository.listApproved()).extracting(SafetyRule::id).doesNotContain(PREFIX + "300").hasSize(4);
  }

  @Test
  void withNoApprovedRulesNothingIsLoaded() {
    jdbc.sql("UPDATE safety.rules SET review_status = 'DRAFT'").update();
    assertThat(repository.listApproved()).isEmpty();
  }
}
