package vn.edu.uit.se214.health.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.edu.uit.se214.health.safety.api.CareLevel;
import vn.edu.uit.se214.health.shared.domain.ReviewStatus;
import vn.edu.uit.se214.health.shared.web.ApiException;
import vn.edu.uit.se214.health.support.TestDatabase;

/** Port of apps/api/tests/safety.test.ts (UC-16 rule drafts). */
class RuleDraftServiceTest {
  static final RuleDraftInput DRAFT =
      new RuleDraftInput(
          "[TEST] Quy tắc thử nghiệm",
          new RuleDraftInput.Conditions(List.of("dau_hieu_thu_nghiem"), List.of()),
          List.of("thoi_diem_bat_dau"),
          CareLevel.INSUFFICIENT_INFO,
          List.of(),
          "Nguồn thử nghiệm",
          "https://example.invalid/rule");

  JdbcClient jdbc;
  RuleDraftService service;

  @BeforeEach
  void freshDatabase() {
    DataSource dataSource = TestDatabase.fresh();
    jdbc = JdbcClient.create(dataSource);
    service = new RuleDraftService(new RuleRepository(jdbc, new ObjectMapper()));
  }

  static RuleDraftInput with(String name, RuleDraftInput.Conditions conditions, String url) {
    return new RuleDraftInput(
        name, conditions, DRAFT.requiredFields(), DRAFT.careLevel(), DRAFT.specialtyIds(), DRAFT.sourceName(), url);
  }

  @Test
  void draftInputRejectsEmptyConditionsNonHttpSourcesAndEmbeddedCredentials() {
    assertThat(DRAFT.validated()).isEqualTo(DRAFT);
    assertThatThrownBy(() -> with(DRAFT.name(), new RuleDraftInput.Conditions(List.of(), List.of()), DRAFT.sourceUrl()).validated())
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> with(DRAFT.name(), DRAFT.conditions(), "file:///local").validated())
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> with(DRAFT.name(), DRAFT.conditions(), "https://user:secret@example.com/rule").validated())
        .isInstanceOf(ApiException.class);
    assertThat(with("  [TEST] có khoảng trắng  ", DRAFT.conditions(), DRAFT.sourceUrl()).validated().name())
        .isEqualTo("[TEST] có khoảng trắng");
  }

  @Test
  void draftCanBeCreatedListedReadAndEditedWithoutBecomingActive() {
    SafetyRule created = service.create(DRAFT);
    assertThat(created.reviewStatus()).isEqualTo(ReviewStatus.DRAFT);
    assertThat(created.isSynthetic()).isFalse();
    assertThat(created.name()).isEqualTo(DRAFT.name());
    assertThat(created.conditions()).isEqualTo(DRAFT.conditions());
    assertThat(created.requiredFields()).containsExactly("thoi_diem_bat_dau");
    assertThat(service.list()).hasSize(1);
    UUID id = UUID.fromString(created.id());
    assertThat(service.get(id)).get().extracting(SafetyRule::sourceName).isEqualTo("Nguồn thử nghiệm");

    SafetyRule edited = service.update(id, with("[TEST] Đã sửa", DRAFT.conditions(), DRAFT.sourceUrl())).orElseThrow();
    assertThat(edited.name()).isEqualTo("[TEST] Đã sửa");
    assertThat(service.get(id)).get().extracting(SafetyRule::name).isEqualTo("[TEST] Đã sửa");
    assertThat(service.get(id)).get().extracting(SafetyRule::reviewStatus).isEqualTo(ReviewStatus.DRAFT);
  }

  @Test
  void approvedRuleCannotBeEditedThroughDraftService() {
    SafetyRule created = service.create(DRAFT);
    UUID id = UUID.fromString(created.id());
    jdbc.sql("UPDATE safety.rules SET review_status = 'APPROVED' WHERE id = ?").param(id).update();
    assertThat(service.update(id, with("changed", DRAFT.conditions(), DRAFT.sourceUrl()))).isEmpty();
    assertThat(service.get(id)).get().extracting(SafetyRule::name).isEqualTo(DRAFT.name());
  }

  @Test
  void invalidDraftDoesNotWriteToTheDatabase() {
    assertThatThrownBy(() -> service.create(with("", DRAFT.conditions(), DRAFT.sourceUrl())))
        .isInstanceOf(ApiException.class);
    assertThat(service.list()).isEmpty();
  }

  @Test
  void submittingDraftRequiresSourceAndNeverApprovesIt() {
    SafetyRule draft = service.create(DRAFT);
    UUID id = UUID.fromString(draft.id());

    jdbc.sql("UPDATE safety.rules SET source_name = '' WHERE id = ?").param(id).update();
    assertThatThrownBy(() -> service.submit(id))
        .isInstanceOf(ApiException.class)
        .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("RULE_SOURCE_REQUIRED"));
    assertThat(service.get(id)).get().extracting(SafetyRule::reviewStatus).isEqualTo(ReviewStatus.DRAFT);

    jdbc.sql("UPDATE safety.rules SET source_name = ?, source_url = '' WHERE id = ?")
        .params(DRAFT.sourceName(), id)
        .update();
    assertThatThrownBy(() -> service.submit(id))
        .isInstanceOf(ApiException.class)
        .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("RULE_SOURCE_REQUIRED"));
    assertThat(service.get(id)).get().extracting(SafetyRule::reviewStatus).isEqualTo(ReviewStatus.DRAFT);

    jdbc.sql("UPDATE safety.rules SET source_url = ? WHERE id = ?")
        .params(DRAFT.sourceUrl(), id)
        .update();
    SafetyRule pending = service.submit(id).orElseThrow();
    assertThat(pending.reviewStatus()).isEqualTo(ReviewStatus.PENDING_REVIEW);
    assertThat(service.submit(id)).isEmpty();
    assertThat(service.get(id)).get().extracting(SafetyRule::reviewStatus)
        .isEqualTo(ReviewStatus.PENDING_REVIEW);
  }

  @Test
  void returnedRuleCanBeResubmittedButApprovedRuleCannot() {
    UUID id = UUID.fromString(service.create(DRAFT).id());
    jdbc.sql("UPDATE safety.rules SET review_status = 'RETURNED' WHERE id = ?").param(id).update();
    assertThat(service.submit(id).orElseThrow().reviewStatus()).isEqualTo(ReviewStatus.PENDING_REVIEW);

    jdbc.sql("UPDATE safety.rules SET review_status = 'APPROVED' WHERE id = ?").param(id).update();
    assertThat(service.submit(id)).isEmpty();
    assertThat(service.get(id)).get().extracting(SafetyRule::reviewStatus).isEqualTo(ReviewStatus.APPROVED);
  }
}
