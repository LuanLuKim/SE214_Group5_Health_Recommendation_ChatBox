package vn.edu.uit.se214.health.knowledge;

import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import vn.edu.uit.se214.health.knowledge.api.ArticleSummary;
import vn.edu.uit.se214.health.knowledge.api.FacilitySummary;
import vn.edu.uit.se214.health.knowledge.api.KnowledgeSearch;
import vn.edu.uit.se214.health.shared.domain.SourceReference;

/** DATA_MODE=mock. Public UI fixtures only, same fixed IDs as database/seeds/001_synthetic.sql. */
@Service
@Profile("!postgres")
public class SyntheticKnowledgeSearch implements KnowledgeSearch {
  private static final Locale VI = Locale.forLanguageTag("vi");
  private static final String VERIFIED_AT = "2026-10-29T00:00:00.000Z"; // Fixture, not a real review.

  static final List<ArticleSummary> ARTICLES =
      List.of(
          new ArticleSummary(
              "00000000-0000-4000-8000-000000000010",
              "[TEST] Kiểm thử bài viết về giấc ngủ",
              "Giấc ngủ",
              new SourceReference(
                  "00000000-0000-4000-8000-000000000010",
                  "Synthetic fixture",
                  "https://example.invalid/articles/sleep",
                  VERIFIED_AT),
              VERIFIED_AT));

  static final List<FacilitySummary> FACILITIES =
      List.of(
          new FacilitySummary(
              "00000000-0000-4000-8000-000000000020",
              "[TEST] Cơ sở giả lập A",
              List.of("Chuyên khoa thử nghiệm"),
              "Tỉnh kiểm thử",
              "Địa chỉ giả lập, không có thật",
              "https://example.invalid/facility",
              new SourceReference(
                  "00000000-0000-4000-8000-000000000020",
                  "Synthetic fixture",
                  "https://example.invalid/facility",
                  VERIFIED_AT)));

  @Override
  public List<ArticleSummary> articles(String query) {
    String q = query.toLowerCase(VI);
    return ARTICLES.stream()
        .filter(a -> (a.title() + " " + a.topic()).toLowerCase(VI).contains(q))
        .toList();
  }

  @Override
  public List<FacilitySummary> facilities(String query) {
    String q = query.toLowerCase(VI);
    return FACILITIES.stream().filter(f -> f.name().toLowerCase(VI).contains(q)).toList();
  }

  @Override
  public String dataStatus() {
    return "SYNTHETIC_DATA";
  }
}
