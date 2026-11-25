package safety;

import java.util.List;
import safety.api.CareLevel;
import shared.domain.SourceReference;

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
