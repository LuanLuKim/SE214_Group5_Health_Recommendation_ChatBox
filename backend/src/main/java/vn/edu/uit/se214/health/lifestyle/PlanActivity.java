package vn.edu.uit.se214.health.lifestyle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One activity of an approved template ({@code lifestyle.templates.activities}). Every activity is daily. */
@JsonIgnoreProperties(ignoreUnknown = true)
record PlanActivity(String id, String title) {}
