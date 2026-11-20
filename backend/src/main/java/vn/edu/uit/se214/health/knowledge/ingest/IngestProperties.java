package vn.edu.uit.se214.health.knowledge.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.knowledge.ingest.*}: loading the M7 crawler output into knowledge.* (SCRUM-48).
 *
 * @param enabled run the loader once at start-up (DATA_MODE=postgres only)
 * @param normalizedDir folder with {@code chunks.jsonl} and {@code articles/*.json} from the normalizer
 * @param translatedDir folder with {@code chunks_vi.jsonl} and {@code db_rows_vi.json} from tools/translate
 */
@ConfigurationProperties(prefix = "app.knowledge.ingest")
public record IngestProperties(boolean enabled, String normalizedDir, String translatedDir) {}
