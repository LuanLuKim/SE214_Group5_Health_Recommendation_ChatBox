package vn.edu.uit.se214.health.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.edu.uit.se214.health.knowledge.api.FacilitySummary;
import vn.edu.uit.se214.health.knowledge.api.KnowledgeSearch.FacilityFilter;
import vn.edu.uit.se214.health.shared.domain.SourceReference;
import vn.edu.uit.se214.health.support.TestDatabase;

/**
 * UC-11 search and detail, on PostgreSQL and on the synthetic fixture with the same cases (port of
 * the facility tests of PR #163, written for the former Express API).
 */
class FacilitySearchTest {
  static final String VERIFIED_AT = "2026-10-29T00:00:00.000Z";

  JdbcClient jdbc;
  JdbcKnowledgeSearch search;

  static String id(int number) {
    return String.format("00000000-0000-4000-8000-%012d", number);
  }

  static FacilitySummary facility(int number, String name, String province, List<String> specialties) {
    return facility(number, name, province, specialties, "Synthetic fixture", "https://example.invalid/source",
        VERIFIED_AT);
  }

  static FacilitySummary facility(int number, String name, String province, List<String> specialties,
      String sourceName, String sourceUrl, String verifiedAt) {
    return new FacilitySummary(id(number), "[TEST] " + name, specialties, province,
        "Địa chỉ giả lập, không có thật", "https://example.invalid/facility",
        new SourceReference(id(number), sourceName, sourceUrl, verifiedAt));
  }

  /** Six facilities that may be shown and five without a source name, source URL or verification date. */
  static List<FacilitySummary> fixtures() {
    List<FacilitySummary> all = new ArrayList<>(List.of(
        facility(1, "Cơ sở Alpha", "Tỉnh A", List.of("Nội tổng quát", "Tim mạch")),
        facility(2, "Cơ sở Beta", "Tỉnh A", List.of("Nội tổng quát")),
        facility(3, "Cơ sở Gamma", "Tỉnh B", List.of("Tim mạch")),
        facility(4, "Trung tâm Delta", "Tỉnh B", List.of("Nội tổng quát", "Tim mạch")),
        facility(5, "Cơ sở Alpha", "Tỉnh A", List.of("Nội tổng quát")),
        facility(6, "Cơ sở 100%_Demo", "Tỉnh C", List.of())));
    all.add(facility(90, "Cơ sở Alpha", "Tỉnh A", List.of("Tim mạch"), "", "https://example.invalid/source", VERIFIED_AT));
    all.add(facility(91, "Cơ sở Alpha", "Tỉnh A", List.of("Tim mạch"), "   ", "https://example.invalid/source", VERIFIED_AT));
    all.add(facility(92, "Cơ sở Alpha", "Tỉnh A", List.of("Tim mạch"), "Synthetic fixture", "", VERIFIED_AT));
    all.add(facility(93, "Cơ sở Alpha", "Tỉnh A", List.of("Tim mạch"), "Synthetic fixture", "   ", VERIFIED_AT));
    all.add(facility(94, "Cơ sở Alpha", "Tỉnh A", List.of("Tim mạch"), "Synthetic fixture",
        "https://example.invalid/source", ""));
    return all;
  }

  /** (q, province, specialty) -> numbers of the facilities expected. */
  static final List<Map.Entry<FacilityFilter, List<Integer>>> CASES = List.of(
      Map.entry(new FacilityFilter("", "", ""), List.of(1, 2, 3, 4, 5, 6)),
      Map.entry(new FacilityFilter("  ALPHA  ", "", ""), List.of(1, 5)),
      Map.entry(new FacilityFilter("", "  TỈNH A  ", ""), List.of(1, 2, 5)),
      Map.entry(new FacilityFilter("", "", "  TIM MẠCH  "), List.of(1, 3, 4)),
      Map.entry(new FacilityFilter("CƠ SỞ", "tỉnh a", ""), List.of(1, 2, 5)),
      Map.entry(new FacilityFilter("CƠ SỞ", "", "tim mạch"), List.of(1, 3)),
      Map.entry(new FacilityFilter("", "tỉnh a", "tim mạch"), List.of(1)),
      Map.entry(new FacilityFilter("alpha", "tỉnh a", "tim mạch"), List.of(1)),
      Map.entry(new FacilityFilter("alpha", "Tỉnh B", ""), List.of()),
      Map.entry(new FacilityFilter("", "Tỉnh", ""), List.of()),
      Map.entry(new FacilityFilter("", "", "Tim"), List.of()),
      Map.entry(new FacilityFilter("không tồn tại", "", ""), List.of()),
      Map.entry(new FacilityFilter("%_", "", ""), List.of(6)),
      Map.entry(new FacilityFilter("' OR 1=1 --", "", ""), List.of()));

  @BeforeEach
  void freshDatabase() {
    jdbc = JdbcClient.create(TestDatabase.fresh());
    search = new JdbcKnowledgeSearch(jdbc);
  }

  void insert(FacilitySummary f) {
    jdbc.sql("""
            INSERT INTO knowledge.facilities
              (id, name, specialties, province, address, official_url, source_name, source_url, verified_at, is_synthetic)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, true)""")
        .params(UUID.fromString(f.id()), f.name(), f.specialties().toArray(String[]::new), f.province(),
            f.address(), f.officialUrl(), f.source().name(), f.source().url(),
            f.source().verifiedAt().isEmpty() ? null : Timestamp.from(Instant.parse(f.source().verifiedAt())))
        .update();
  }

  static List<String> ids(List<Integer> numbers) {
    return numbers.stream().map(FacilitySearchTest::id).sorted().toList();
  }

  void checkCases(Function<FacilityFilter, List<FacilitySummary>> lookup) {
    for (var c : CASES) {
      List<FacilitySummary> found = lookup.apply(c.getKey());
      assertThat(found.stream().map(FacilitySummary::id).sorted().toList()).as(c.getKey().toString())
          .isEqualTo(ids(c.getValue()));
      for (FacilitySummary f : found) {
        assertThat(f.source().name()).isEqualTo("Synthetic fixture");
        assertThat(f.source().url()).isEqualTo("https://example.invalid/source");
        assertThat(f.source().verifiedAt()).isEqualTo(VERIFIED_AT);
      }
    }
  }

  @Test
  void postgresFiltersAloneAndCombinedWithAnd() {
    fixtures().forEach(this::insert);
    checkCases(search::facilities);
  }

  @Test
  void syntheticRulesMatchPostgres() {
    checkCases(filter -> SyntheticKnowledgeSearch.facilities(fixtures(), filter));
  }

  @Test
  void detailReturnsOnlySourcedVerifiedFacilities() {
    List<FacilitySummary> all = fixtures();
    all.forEach(this::insert);
    assertThat(search.facility(UUID.fromString(id(1)))).contains(all.get(0));
    assertThat(search.facility(UUID.fromString(id(999)))).isEmpty();
    for (int hidden : List.of(90, 91, 92, 93, 94))
      assertThat(search.facility(UUID.fromString(id(hidden)))).as(id(hidden)).isEmpty();
    // the lookup M1 uses returns the same record as the detail
    assertThat(search.facilities(new FacilityFilter("", "tỉnh a", "tim mạch")))
        .containsExactly(search.facility(UUID.fromString(id(1))).orElseThrow());
  }

  @Test
  void sameNameIsOrderedById() {
    fixtures().forEach(this::insert);
    assertThat(search.facilities(new FacilityFilter("alpha", "", "")).stream().map(FacilitySummary::id))
        .containsExactly(id(1), id(5));
  }

  @Test
  void atMostTwentyInStableNameOrder() {
    List<FacilitySummary> records = IntStream.range(0, 23)
        .mapToObj(i -> facility(100 + i, String.format("Limit %02d", i), "Tỉnh thử", List.of()))
        .toList();
    new ArrayList<>(records).reversed().forEach(this::insert);
    List<String> expected = records.subList(0, 20).stream().map(FacilitySummary::id).toList();
    assertThat(search.facilities(new FacilityFilter("[TEST] Limit", "", "")).stream().map(FacilitySummary::id))
        .containsExactlyElementsOf(expected);
    assertThat(SyntheticKnowledgeSearch.facilities(records.reversed(), new FacilityFilter("[TEST] Limit", "", ""))
        .stream().map(FacilitySummary::id)).containsExactlyElementsOf(expected);
  }
}
