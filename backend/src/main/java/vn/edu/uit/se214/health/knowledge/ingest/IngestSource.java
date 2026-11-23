package vn.edu.uit.se214.health.knowledge.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Reads the M7 files into rows for knowledge.articles and knowledge.chunks. The Vietnamese text
 * comes from tools/translate; crawl time, rule tags and normalizer version from the normalizer.
 * Only the RAG corpus is read: pages kept as expert reference (medicine pages) never reach these rows.
 * Review decision files (SCRUM-148) give the texts an expert approved and the decisions to record.
 */
final class IngestSource {
  static final String RAG = "rag";

  /** One knowledge.articles row. {@code verified_at} is never set here: only UC-17 approves. */
  record ArticleRow(
      UUID id,
      String title,
      String topic,
      String content,
      String sourceName,
      String sourceUrl,
      OffsetDateTime sourceUpdatedAt) {}

  /** One knowledge.chunks row (column names as proposed on #50). */
  record ChunkRow(
      UUID id,
      UUID articleId,
      String chunkKey,
      String heading,
      List<String> headingPath,
      String text,
      String originalText,
      String language,
      int wordCount,
      String textSha256,
      List<String> scopeTags,
      List<String> ruleTags,
      String sourceUrl,
      OffsetDateTime fetchedAt,
      String normalizerVersion,
      String translationModel,
      TranslationStatus translationStatus) {}

  /** One expert decision on a chunk, with the time it was recorded (no time: not recorded as an event). */
  record ReviewDecision(UUID chunkId, String decision, String note, OffsetDateTime recordedAt) {}

  record Batch(
      List<ArticleRow> articles, List<ChunkRow> chunks, int skippedOutsideRag, List<ReviewDecision> decisions) {}

  private record Decisions(Map<UUID, Set<String>> approvedTexts, List<ReviewDecision> timed) {}

  private final ObjectMapper json;
  private final String translationModel;

  /**
   * @param translationModel {@code translation_model} of every translated chunk, from configuration;
   *     blank or null leaves it NULL. Any model named in the files is ignored.
   */
  IngestSource(ObjectMapper json, String translationModel) {
    this.json = json;
    this.translationModel = translationModel == null || translationModel.isBlank() ? null : translationModel.strip();
  }

  Batch read(Path normalizedDir, Path translatedDir, List<Path> decisionFiles) {
    Decisions decisions = decisions(decisionFiles);
    Map<UUID, JsonNode> normalizedChunks = byId(lines(normalizedDir.resolve("chunks.jsonl")), "id");
    Map<UUID, JsonNode> normalizedArticles = byId(articleFiles(normalizedDir.resolve("articles")), "uuid");
    int skipped = 0;

    List<ArticleRow> articles = new ArrayList<>();
    for (JsonNode row : json(translatedDir.resolve("db_rows_vi.json"))) {
      if (!RAG.equals(text(row, "corpus"))) {
        skipped++;
        continue;
      }
      UUID id = UUID.fromString(required(row, "id"));
      JsonNode normalized = normalizedArticles.get(id);
      articles.add(new ArticleRow(
          id,
          required(row, "title"),
          required(row, "topic"),
          required(row, "content"),
          required(row, "source_name"),
          required(row, "source_url"),
          normalized == null ? null : time(normalized, "source_updated_at")));
    }

    List<ChunkRow> chunks = new ArrayList<>();
    for (JsonNode row : lines(translatedDir.resolve("chunks_vi.jsonl"))) {
      if (!RAG.equals(text(row, "corpus"))) {
        skipped++;
        continue;
      }
      UUID id = UUID.fromString(required(row, "chunk_id"));
      JsonNode normalized = normalizedChunks.get(id);
      if (normalized == null) throw new IngestException("chunk " + id + " is missing from chunks.jsonl");
      JsonNode meta = row.path("translation_metadata");
      String vietnamese = required(row, "text_vi");
      TranslationStatus status =
          TranslationStatus.of(meta, sha256(vietnamese), decisions.approvedTexts().getOrDefault(id, Set.of()));
      String english = text(row, "text_en");
      chunks.add(new ChunkRow(
          id,
          UUID.fromString(required(row, "article_uuid")),
          required(row, "chunk_key"),
          text(row, "heading_vi"),
          strings(row.path("heading_path_vi")),
          vietnamese,
          status == null || english == null || english.isBlank() ? null : english,
          required(row, "language"),
          row.path("word_count_vi").asInt(),
          sha256(vietnamese),
          strings(row.path("scope_tags")),
          strings(normalized.path("rule_tags")),
          required(row, "source_url"),
          requiredTime(normalized, "fetched_at", id),
          text(normalized, "normalizer_version"),
          status == null ? null : translationModel,
          status));
    }
    Set<UUID> loaded = new HashSet<>();
    chunks.forEach(c -> loaded.add(c.id()));
    List<ReviewDecision> timed = decisions.timed().stream().filter(d -> loaded.contains(d.chunkId())).toList();
    return new Batch(articles, chunks, skipped, timed);
  }

  /**
   * {@code {"decisions": [{chunk_id, decision, note|notes, corrected_text_vi_sha256|reviewed_text_vi_sha256,
   * approval_recorded_at?}]}}. The hash is of the Vietnamese lines joined with LF, as the loaded text.
   */
  private Decisions decisions(List<Path> files) {
    Map<UUID, Set<String>> approved = new HashMap<>();
    List<ReviewDecision> timed = new ArrayList<>();
    for (Path file : files) {
      for (JsonNode d : json(file).path("decisions")) {
        UUID chunk = UUID.fromString(required(d, "chunk_id"));
        String decision = required(d, "decision");
        if (!decision.equals("APPROVED") && !decision.equals("RETURNED"))
          throw new IngestException("decision " + decision + " is neither APPROVED nor RETURNED");
        String hash = text(d, "corrected_text_vi_sha256");
        if (hash == null) hash = text(d, "reviewed_text_vi_sha256");
        if (decision.equals("APPROVED") && hash != null)
          approved.computeIfAbsent(chunk, k -> new HashSet<>()).add(hash);
        OffsetDateTime at = time(d, "approval_recorded_at");
        String note = text(d, "notes");
        if (at != null) timed.add(new ReviewDecision(chunk, decision, note == null ? text(d, "note") : note, at));
      }
    }
    return new Decisions(approved, timed);
  }

  private List<JsonNode> lines(Path file) {
    try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
      return lines.filter(l -> !l.isBlank()).map(this::parse).toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
  }

  private JsonNode json(Path file) {
    try {
      return json.readTree(file.toFile());
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
  }

  private List<JsonNode> articleFiles(Path dir) {
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().map(this::json).toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + dir, e);
    }
  }

  private JsonNode parse(String line) {
    try {
      return json.readTree(line);
    } catch (IOException e) {
      throw new IngestException("a line is not valid JSON");
    }
  }

  private static Map<UUID, JsonNode> byId(List<JsonNode> rows, String field) {
    Map<UUID, JsonNode> map = new HashMap<>();
    for (JsonNode row : rows) {
      String id = text(row, field);
      if (id != null) map.put(UUID.fromString(id), row);
    }
    return map;
  }

  private static String text(JsonNode row, String field) {
    JsonNode node = row.get(field);
    return node == null || node.isNull() ? null : node.asText();
  }

  private static String required(JsonNode row, String field) {
    String value = text(row, field);
    if (value == null || value.isBlank()) throw new IngestException("field " + field + " is missing");
    return value;
  }

  private static OffsetDateTime time(JsonNode row, String field) {
    String value = text(row, field);
    return value == null || value.isBlank() ? null : OffsetDateTime.parse(value);
  }

  /** Every chunk keeps its real crawl time (no common date): it is what tells a reviewer how old it is. */
  private static OffsetDateTime requiredTime(JsonNode row, String field, UUID chunk) {
    OffsetDateTime value = time(row, field);
    if (value == null) throw new IngestException("chunk " + chunk + " has no " + field);
    return value;
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    if (array != null && array.isArray()) array.forEach(v -> values.add(v.asText()));
    return List.copyOf(values);
  }

  static String sha256(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
