package lifestyle;

import java.time.LocalDate;
import java.util.UUID;

/** The user's current plan joined with its template, as stored. {@code activitiesJson} is parsed by the service. */
record ActivePlan(
    UUID id, UUID templateId, String templateTitle, LocalDate startedOn, String activitiesJson) {}
