package vn.edu.uit.se214.health.knowledge.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public search of M2 (UC-10, UC-11). Implementations return only approved, verified content.
 * M4 uses the same contract to retrieve sources for answers.
 */
public interface KnowledgeSearch {
  int MAX_ARTICLE_PAGE_SIZE = 20;

  default List<ArticleSummary> articles(String query) {
    return articles(query, "", MAX_ARTICLE_PAGE_SIZE, 0).items();
  }

  ArticlePage articles(String query, String topic, int limit, long offset);

  Optional<ArticleDetail> article(UUID id);

  List<FacilitySummary> facilities(String query);

  /** SYNTHETIC_DATA or DATABASE_RESULTS, shown to the UI so test data is never mistaken for real. */
  String dataStatus();

  record ArticlePage(List<ArticleSummary> items, int total) {}
}
