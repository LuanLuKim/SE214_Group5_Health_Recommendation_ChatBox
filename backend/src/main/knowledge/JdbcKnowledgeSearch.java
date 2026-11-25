package knowledge;

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
import knowledge.api.ArticleDetail;
import knowledge.api.ArticleSummary;
import knowledge.api.FacilitySummary;
import knowledge.api.KnowledgeSearch;
import shared.domain.Instants;
import shared.domain.SourceReference;

/** DATA_MODE=postgres. Only APPROVED, verified articles and sourced, verified facilities are returned. */
@Service
@Profile("postgres")
public class JdbcKnowledgeSearch implements KnowledgeSearch {
  private static final String PUBLISHED = "review_status = 'APPROVED' AND verified_at IS NOT NULL";
  private static final String ARTICLE_FILTER =
      " WHERE " + PUBLISHED + " AND (title ILIKE ? OR topic ILIKE ?)"
          + " AND (? = '' OR lower(topic) = lower(?))";
  /** UC-11: a facility is shown only with a source name, a source URL and a verification date. */
  private static final String VERIFIED_FACILITY =
      "verified_at IS NOT NULL AND btrim(source_name) <> '' AND btrim(source_url) <> ''";
  private static final String FACILITY_FIELDS =
      "id, name, specialties, province, address, official_url, source_name, source_url, verified_at";

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
  public List<FacilitySummary> facilities(FacilityFilter filter) {
    return jdbc.sql(
            "SELECT " + FACILITY_FIELDS + " FROM knowledge.facilities WHERE " + VERIFIED_FACILITY
                + """
                 AND name ILIKE ?
                  AND (? = '' OR lower(province) = lower(?))
                  AND (? = '' OR EXISTS (
                    SELECT 1 FROM unnest(specialties) AS s(value) WHERE lower(s.value) = lower(?)))
                ORDER BY name, id LIMIT ?""")
        .params(like(filter.query()), filter.province(), filter.province(), filter.specialty(),
            filter.specialty(), MAX_FACILITIES)
        .query((rs, row) -> facility(rs))
        .list();
  }

  @Override
  public Optional<FacilitySummary> facility(UUID id) {
    return jdbc.sql(
            "SELECT " + FACILITY_FIELDS + " FROM knowledge.facilities WHERE id = ? AND "
                + VERIFIED_FACILITY)
        .param(id)
        .query((rs, row) -> facility(rs))
        .optional();
  }

  private static FacilitySummary facility(ResultSet rs) throws SQLException {
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
