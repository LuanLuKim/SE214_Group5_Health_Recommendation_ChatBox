package vn.edu.uit.se214.health.safety;

import java.util.List;
import vn.edu.uit.se214.health.safety.api.CareLevel;
import vn.edu.uit.se214.health.shared.domain.SourceReference;

/** A reviewed, activated rule payload for the internal FastAPI Safety Gate. */
public record ApprovedSafetyRule(
    String id,
    RuleDraftInput.Conditions conditions,
    List<String> requiredFields,
    CareLevel careLevel,
    List<String> specialtyIds,
    SourceReference source,
    String reviewStatus,
    boolean active) {}
