package vn.edu.uit.se214.health.lifestyle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One activity of an approved template ({@code lifestyle.templates.activities}). Every activity is
 * daily. {@code description} is the optional note shown in the UC-12 preview.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanActivity(String id, String title, String description) {
  private static final Logger log = LoggerFactory.getLogger(PlanActivity.class);
  private static final TypeReference<List<PlanActivity>> LIST = new TypeReference<>() {};

  /**
   * Activities with an id, first occurrence wins. A template whose JSON cannot be read has no
   * activities; only its id is logged, never its content.
   */
  static List<PlanActivity> parse(ObjectMapper json, String activitiesJson, UUID templateId) {
    List<PlanActivity> parsed;
    try {
      parsed = activitiesJson == null ? List.of() : json.readValue(activitiesJson, LIST);
    } catch (Exception e) {
      log.warn("Lifestyle template {} has unreadable activities", templateId);
      return List.of();
    }
    Map<String, PlanActivity> unique = new LinkedHashMap<>();
    for (PlanActivity activity : parsed == null ? List.<PlanActivity>of() : parsed) {
      if (activity == null || activity.id() == null || activity.id().isBlank()) continue;
      unique.putIfAbsent(activity.id(), activity);
    }
    return List.copyOf(unique.values());
  }
}
