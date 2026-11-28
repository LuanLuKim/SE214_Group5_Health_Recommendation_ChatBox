package knowledge.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Set;

/**
 * Result of the translation quality gate for one chunk (knowledge.chunks.translation_status), as agreed with
 * M2 on 20/11. When an article is approved, only chunks that passed the content review and are
 * {@code auto_verified} or {@code reviewer_corrected} are switched on. A chunk written in Vietnamese at the
 * source was never translated and has no status (NULL).
 */
enum TranslationStatus {
  /** No gate result yet. */
  PENDING,
  /** Passed the fixed rules and the meaning check, no warning left. */
  AUTO_VERIFIED,
  /** Warning, failed gate or meaning check still open: waits for a reviewer. */
  FLAGGED,
  /** Passed, has reviewer corrections, and this exact text was checked again and approved by the expert. */
  REVIEWER_CORRECTED;

  static final String NATIVE = "passthrough_native";

  String value() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * {@code translation_metadata} of a chunks_vi.jsonl row; null for a chunk not translated.
   *
   * @param textSha256 SHA-256 of the Vietnamese text being loaded
   * @param approved SHA-256 of the texts of this chunk an expert approved (review decision files)
   */
  static TranslationStatus of(JsonNode meta, String textSha256, Set<String> approved) {
    String quality = meta.path("quality_status").asText("");
    if (NATIVE.equals(quality)) return null;
    if (quality.isBlank()) return PENDING;
    if (!"auto_verified".equals(quality) || meta.path("needs_human_review").asBoolean(true)) return FLAGGED;
    boolean corrected = meta.path("human_corrected_lines").asInt(0) > 0;
    return corrected && approved.contains(textSha256) ? REVIEWER_CORRECTED : AUTO_VERIFIED;
  }
}
