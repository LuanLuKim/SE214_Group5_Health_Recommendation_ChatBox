package vn.edu.uit.se214.health.knowledge.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

/**
 * Result of the translation quality gate for one chunk (knowledge.chunks.translation_status). When an
 * article is approved, only chunks that passed both the content review and this gate are switched on.
 * A chunk written in Vietnamese at the source was never translated and has no status (NULL).
 */
enum TranslationStatus {
  /** Passed the fixed rules and the meaning check, no warning left. */
  AUTO_VERIFIED,
  /** Warning, failed gate or meaning check still open: waits for a reviewer. */
  FLAGGED,
  /** Passed, and some lines are the reviewer's own corrections. */
  REVIEWER_CORRECTED;

  static final String NATIVE = "passthrough_native";

  String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** {@code translation_metadata} of a chunks_vi.jsonl row; null for a chunk not translated. */
  static TranslationStatus of(JsonNode meta) {
    String quality = meta.path("quality_status").asText("");
    if (NATIVE.equals(quality)) return null;
    if (!"auto_verified".equals(quality) || meta.path("needs_human_review").asBoolean(true)) return FLAGGED;
    return meta.path("human_corrected_lines").asInt(0) > 0 ? REVIEWER_CORRECTED : AUTO_VERIFIED;
  }
}
