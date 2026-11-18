package vn.edu.uit.se214.health.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.edu.uit.se214.health.safety.api.CareLevel;
import vn.edu.uit.se214.health.support.TestDatabase;

class ApprovedRuleRepositoryTest {
  @Test
  void onlyApprovedActiveVerifiedNonsyntheticRulesReachTheEvaluator() {
    DataSource db = TestDatabase.fresh();
    JdbcClient jdbc = JdbcClient.create(db);
    RuleRepository repository = new RuleRepository(jdbc, new ObjectMapper());
    RuleDraftInput draft =
        new RuleDraftInput(
            "[TEST] synthetic condition",
            new RuleDraftInput.Conditions(List.of("synthetic_sign"), List.of()),
            List.of("onset"),
            CareLevel.SEEK_CARE_SOON,
            List.of("synthetic-specialty"),
            "[TEST] source",
            "https://example.invalid/source");

    SafetyRule selected = repository.create(draft);
    SafetyRule draftRule = repository.create(draft);
    SafetyRule inactive = repository.create(draft);
    SafetyRule unverified = repository.create(draft);
    SafetyRule synthetic = repository.create(draft);
    SafetyRule invalidSource = repository.create(draft);
    jdbc.sql(
            "UPDATE safety.rules SET review_status='APPROVED', is_active=true, source_verified_at=now() WHERE id=?")
        .param(UUID.fromString(selected.id()))
        .update();
    jdbc.sql(
            "UPDATE safety.rules SET source_verified_at=now() WHERE id=?")
        .param(UUID.fromString(draftRule.id()))
        .update();
    jdbc.sql(
            "UPDATE safety.rules SET review_status='APPROVED', source_verified_at=now() WHERE id=?")
        .param(UUID.fromString(inactive.id()))
        .update();
    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE safety.rules SET review_status='APPROVED', is_active=true WHERE id=?")
                    .param(UUID.fromString(unverified.id()))
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.sql("UPDATE safety.rules SET review_status='APPROVED' WHERE id=?")
        .param(UUID.fromString(unverified.id()))
        .update();
    jdbc.sql(
            "UPDATE safety.rules SET review_status='APPROVED', is_active=true, is_synthetic=true, source_verified_at=now() WHERE id=?")
        .param(UUID.fromString(synthetic.id()))
        .update();
    jdbc.sql(
            "UPDATE safety.rules SET review_status='APPROVED', is_active=true, source_verified_at=now(), source_url='file:///invalid' WHERE id=?")
        .param(UUID.fromString(invalidSource.id()))
        .update();

    List<ApprovedSafetyRule> rules = repository.listEvaluable();
    assertThat(rules).extracting(ApprovedSafetyRule::id).containsExactly(selected.id());
    assertThat(rules.getFirst().source().verifiedAt()).isNotBlank();
    assertThat(rules.getFirst().conditions().all()).containsExactly("synthetic_sign");
  }
}
