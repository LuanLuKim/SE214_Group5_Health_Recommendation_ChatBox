package knowledge;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import knowledge.api.ArticleDetail;
import knowledge.api.ArticleSummary;
import knowledge.api.FacilitySummary;
import knowledge.api.KnowledgeSearch;
import shared.domain.SourceReference;

/** DATA_MODE=mock. Public UI fixtures only, same fixed IDs as database/seeds/001_synthetic.sql. */
@Service
@Profile("!postgres")
public class SyntheticKnowledgeSearch implements KnowledgeSearch {
  private static final Locale VI = Locale.forLanguageTag("vi");
  private static final String VERIFIED_AT = "2026-10-29T00:00:00.000Z"; // Fixture, not a real review.

  static final List<ArticleDetail> ARTICLES =
      List.of(
          new ArticleDetail(
              "00000000-0000-4000-8000-000000000010",
              "[TEST] Kiểm thử bài viết về giấc ngủ",
              "Giấc ngủ",
              "Nội dung giả lập để kiểm tra giao diện. Không có hướng dẫn y tế.",
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
  public ArticlePage articles(String query, String topic, int limit, long offset) {
    String q = query.toLowerCase(VI);
    String normalizedTopic = topic.toLowerCase(VI);
    List<ArticleDetail> matches = ARTICLES.stream()
        .filter(a -> a.title().toLowerCase(VI).contains(q) || a.topic().toLowerCase(VI).contains(q))
        .filter(a -> normalizedTopic.isEmpty() || a.topic().toLowerCase(VI).equals(normalizedTopic))
        .sorted(Comparator.comparing(ArticleDetail::title).thenComparing(ArticleDetail::id))
        .toList();
    return new ArticlePage(
        matches.stream().skip(offset).limit(limit)
            .map(a -> new ArticleSummary(a.id(), a.title(), a.topic(), a.source(), a.updatedAt()))
            .toList(),
        matches.size());
  }

  @Override
  public Optional<ArticleDetail> article(UUID id) {
    return ARTICLES.stream().filter(a -> a.id().equals(id.toString())).findFirst();
  }

  @Override
  public List<FacilitySummary> facilities(FacilityFilter filter) {
    return facilities(FACILITIES, filter);
  }

  @Override
  public Optional<FacilitySummary> facility(UUID id) {
    return FACILITIES.stream()
        .filter(f -> f.id().equals(id.toString()) && verified(f))
        .findFirst();
  }

  /** Same rules as {@link JdbcKnowledgeSearch#facilities}, on a list (the fixture, or a test's). */
  static List<FacilitySummary> facilities(List<FacilitySummary> all, FacilityFilter filter) {
    String q = filter.query().toLowerCase(VI);
    String province = filter.province().toLowerCase(VI);
    String specialty = filter.specialty().toLowerCase(VI);
    return all.stream()
        .filter(SyntheticKnowledgeSearch::verified)
        .filter(f -> f.name().toLowerCase(VI).contains(q))
        .filter(f -> province.isEmpty() || f.province().toLowerCase(VI).equals(province))
        .filter(f -> specialty.isEmpty()
            || f.specialties().stream().anyMatch(s -> s.toLowerCase(VI).equals(specialty)))
        .sorted(Comparator.comparing(FacilitySummary::name).thenComparing(FacilitySummary::id))
        .limit(MAX_FACILITIES)
        .toList();
  }

  static boolean verified(FacilitySummary f) {
    return f.source() != null
        && f.source().name() != null && !f.source().name().isBlank()
        && f.source().url() != null && !f.source().url().isBlank()
        && f.source().verifiedAt() != null && !f.source().verifiedAt().isBlank();
  }

  @Override
  public String dataStatus() {
    return "SYNTHETIC_DATA";
  }
}
