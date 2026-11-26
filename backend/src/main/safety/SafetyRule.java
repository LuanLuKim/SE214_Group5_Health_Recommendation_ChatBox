package safety;

import java.util.List;
import safety.api.CareLevel;
import shared.domain.ReviewStatus;

/** A stored rule (draft or reviewed). Timestamps are ISO-8601 instants. */
public record SafetyRule(
    String id,
    String name,
    RuleDraftInput.Conditions conditions,
    List<String> requiredFields,
    CareLevel careLevel,
    List<String> specialtyIds,
    String sourceName,
    String sourceUrl,
    ReviewStatus reviewStatus,
    boolean isSynthetic,
    String createdAt,
    String updatedAt) {}
