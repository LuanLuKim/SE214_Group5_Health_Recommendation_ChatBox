package vn.edu.uit.se214.health.knowledge;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import vn.edu.uit.se214.health.knowledge.api.ArticleSummary;
import vn.edu.uit.se214.health.knowledge.api.FacilitySummary;
import vn.edu.uit.se214.health.knowledge.api.KnowledgeSearch;
import vn.edu.uit.se214.health.shared.domain.Instants;
import vn.edu.uit.se214.health.shared.domain.SourceReference;

/** DATA_MODE=postgres. Only APPROVED, verified articles and verified facilities are returned. */
@Service
@Profile("postgres")
public class JdbcKnowledgeSearch implements KnowledgeSearch {
  private final JdbcClient jdbc;

  public JdbcKnowledgeSearch(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<ArticleSummary> articles(String query) {
    return jdbc.sql(
            """
            SELECT id, title, topic, source_name, source_url, verified_at, updated_at
            FROM knowledge.articles
            WHERE review_status = 'APPROVED' AND verified_at IS NOT NULL
              AND (title ILIKE ? OR topic ILIKE ?)
            ORDER BY title LIMIT 20""")
        .params(like(query), like(query))
        .query(
            (rs, row) -> {
              String id = rs.getString("id");
              return new ArticleSummary(
                  id,
                  rs.getString("title"),
                  rs.getString("topic"),
                  new SourceReference(
                      id,
                      rs.getString("source_name"),
                      rs.getString("source_url"),
                      Instants.iso(rs.getObject("verified_at", OffsetDateTime.class))),
                  Instants.iso(rs.getObject("updated_at", OffsetDateTime.class)));
            })
        .list();
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
