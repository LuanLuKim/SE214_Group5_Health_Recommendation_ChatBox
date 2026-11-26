package knowledge.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public search of M2 (UC-10, UC-11). Implementations return only approved, verified content.
 * M4 uses the same contract to retrieve sources for answers.
 */
public interface KnowledgeSearch {
  int MAX_ARTICLE_PAGE_SIZE = 20;
  int MAX_FACILITIES = 20;

  default List<ArticleSummary> articles(String query) {
    return articles(query, "", MAX_ARTICLE_PAGE_SIZE, 0).items();
  }

  ArticlePage articles(String query, String topic, int limit, long offset);

  Optional<ArticleDetail> article(UUID id);

  /**
   * UC-11: at most {@link #MAX_FACILITIES} facilities with a source name, source URL and
   * verification date, ordered by name then id. Every filter of {@code filter} that is not empty
   * must match (AND); M1 (Safety Gate) uses the same lookup.
   */
  List<FacilitySummary> facilities(FacilityFilter filter);

  default List<FacilitySummary> facilities(String query) {
    return facilities(new FacilityFilter(query, "", ""));
  }

  /** UC-11 detail: the facility, only when it has a source and a verification date. */
  Optional<FacilitySummary> facility(UUID id);

  /** Verified catalogue matches only. Synthetic implementations must not recommend facilities. */
  default List<FacilitySummary> facilitiesBySpecialties(List<String> specialties) {
    return List.of();
  }

  /** SYNTHETIC_DATA or DATABASE_RESULTS, shown to the UI so test data is never mistaken for real. */
  String dataStatus();

  record ArticlePage(List<ArticleSummary> items, int total) {}

  /**
   * UC-11 filters, each trimmed, empty when not used. {@code query} is part of the name,
   * {@code province} and {@code specialty} are whole names of the directory; none is case sensitive.
   * {@code specialty} is a directory label, not a {@code SafetyDecision} specialty id: M1 maps its ids
   * to directory names before the lookup.
   */
  record FacilityFilter(String query, String province, String specialty) {
    public FacilityFilter {
      query = query == null ? "" : query.trim();
      province = province == null ? "" : province.trim();
      specialty = specialty == null ? "" : specialty.trim();
    }
  }
}
