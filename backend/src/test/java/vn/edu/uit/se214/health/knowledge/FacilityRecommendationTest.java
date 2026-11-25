package vn.edu.uit.se214.health.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.edu.uit.se214.health.support.TestDatabase;

/** Isolated fixtures only; no facilities are inserted into the production catalogue. */
class FacilityRecommendationTest {
  @Test
  void recommendsOnlyExactVerifiedNonSyntheticSourcedMatches() {
    JdbcClient jdbc = JdbcClient.create(TestDatabase.fresh());
    JdbcKnowledgeSearch search = new JdbcKnowledgeSearch(jdbc);
    UUID valid = insert(jdbc, "cardiology", true, false, "Source", "https://example.org/source");
    insert(jdbc, "cardiology", true, true, "Source", "https://example.org/source");
    insert(jdbc, "cardiology", false, false, "Source", "https://example.org/source");
    insert(jdbc, "cardiology", true, false, " ", "https://example.org/source");
    insert(jdbc, "cardiology", true, false, "Source", "not-a-url");
    insert(jdbc, "neurology", true, false, "Source", "https://example.org/source");

    var result = search.facilitiesBySpecialties(List.of("cardiology", "cardiology"));
    assertEquals(1, result.size());
    assertEquals(valid.toString(), result.getFirst().id());
    assertNotNull(result.getFirst().source().verifiedAt());
    assertTrue(search.facilitiesBySpecialties(List.of()).isEmpty());
    assertTrue(search.facilitiesBySpecialties(null).isEmpty());
    assertTrue(search.facilitiesBySpecialties(List.of("cardio")).isEmpty());
    assertTrue(search.facilitiesBySpecialties(List.of("cardiology') OR true --")).isEmpty());
  }

  private UUID insert(
      JdbcClient jdbc, String specialty, boolean verified, boolean synthetic, String source, String url) {
    UUID id = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO knowledge.facilities
          (id, name, specialties, province, address, official_url,
           source_name, source_url, verified_at, is_synthetic)
        VALUES (?, '[TEST] isolated fixture', ARRAY[?]::text[], 'test', 'test',
                'https://example.org', ?, ?, CASE WHEN ? THEN now() ELSE NULL END, ?)
        """)
        .params(id, specialty, source, url, verified, synthetic)
        .update();
    return id;
  }
}