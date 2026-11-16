package vn.edu.uit.se214.health.knowledge;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.uit.se214.health.knowledge.api.ArticleDetail;
import vn.edu.uit.se214.health.knowledge.api.ArticleSummary;
import vn.edu.uit.se214.health.knowledge.api.FacilitySummary;
import vn.edu.uit.se214.health.knowledge.api.KnowledgeSearch;
import vn.edu.uit.se214.health.shared.domain.Instants;
import vn.edu.uit.se214.health.shared.domain.SourceReference;

/** DATA_MODE=postgres. Only APPROVED, verified articles and verified facilities are returned. */
@Service
@Profile("postgres")
public class JdbcKnowledgeSearch implements KnowledgeSearch {
  private static final String PUBLISHED = "review_status = 'APPROVED' AND verified_at IS NOT NULL";
  private static final String ARTICLE_FILTER =
      " WHERE " + PUBLISHED + " AND (title ILIKE ? OR topic ILIKE ?)"
          + " AND (? = '' OR lower(topic) = lower(?))";

  private final JdbcClient jdbc;

  public JdbcKnowledgeSearch(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ArticlePage articles(String query, String topic, int limit, long offset) {
    String pattern = like(query);
    int total =
        jdbc.sql("SELECT count(*) FROM knowledge.articles" + ARTICLE_FILTER)
            .params(pattern, pattern, topic, topic)
            .query(Integer.class)
            .single();
    List<ArticleSummary> items = jdbc.sql(
            "SELECT id, title, topic, source_name, source_url, verified_at, updated_at"
                + " FROM knowledge.articles" + ARTICLE_FILTER + " ORDER BY title, id LIMIT ? OFFSET ?")
        .params(pattern, pattern, topic, topic, limit, offset)
        .query((rs, row) -> new ArticleSummary(
            rs.getString("id"),
            rs.getString("title"),
            rs.getString("topic"),
            articleSource(rs),
            Instants.iso(rs.getObject("updated_at", OffsetDateTime.class))))
        .list();
    return new ArticlePage(items, total);
  }

  @Override
  public Optional<ArticleDetail> article(UUID id) {
    return jdbc.sql(
            "SELECT id, title, topic, content, source_name, source_url, verified_at, updated_at"
                + " FROM knowledge.articles WHERE id = ? AND " + PUBLISHED)
        .param(id)
        .query((rs, row) -> new ArticleDetail(
            rs.getString("id"),
            rs.getString("title"),
            rs.getString("topic"),
            rs.getString("content"),
            articleSource(rs),
            Instants.iso(rs.getObject("updated_at", OffsetDateTime.class))))
        .optional();
  }

  private static SourceReference articleSource(ResultSet rs) throws SQLException {
    return new SourceReference(
        rs.getString("id"),
        rs.getString("source_name"),
        rs.getString("source_url"),
        Instants.iso(rs.getObject("verified_at", OffsetDateTime.class)));
  }

  @Override
  public List<FacilitySummary> facilities(String query) {
    return jdbc.sql(
            """
            SELECT id, name, specialties, province, address, official_url,
                   source_name, source_url, verified_at
            FROM knowledge.facilities
            WHERE verified_at IS NOT NULL AND name ILIKE ?
            ORDER BY name LIMIT 20""")
        .param(like(query))
        .query(
            (rs, row) -> {
              String id = rs.getString("id");
              return new FacilitySummary(
                  id,
                  rs.getString("name"),
                  strings(rs.getArray("specialties")),
                  rs.getString("province"),
                  rs.getString("address"),
                  rs.getString("official_url"),
                  new SourceReference(
                      id,
                      rs.getString("source_name"),
                      rs.getString("source_url"),
                      Instants.iso(rs.getObject("verified_at", OffsetDateTime.class))));
            })
        .list();
  }

  @Override
  public String dataStatus() {
    return "DATABASE_RESULTS";
  }

  /** Escapes LIKE wildcards so user input is matched literally. */
  private static String like(String query) {
    return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }

  private static List<String> strings(Array array) throws SQLException {
    return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
  }
}
