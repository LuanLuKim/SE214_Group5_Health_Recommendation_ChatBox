package vn.edu.uit.se214.health.knowledge.api;

import java.util.List;

/**
 * Public search of M2 (UC-10, UC-11). Implementations return only approved, verified content.
 * M4 uses the same contract to retrieve sources for answers.
 */
public interface KnowledgeSearch {
  List<ArticleSummary> articles(String query);

  List<FacilitySummary> facilities(String query);

  /** Verified catalogue matches only. Synthetic implementations must not recommend facilities. */
  default List<FacilitySummary> facilitiesBySpecialties(List<String> specialties) {
    return List.of();
  }

  /** SYNTHETIC_DATA or DATABASE_RESULTS, shown to the UI so test data is never mistaken for real. */
  String dataStatus();
}
