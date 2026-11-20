package vn.edu.uit.se214.health.knowledge.ingest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;
import vn.edu.uit.se214.health.knowledge.ingest.IngestSource.ArticleRow;
import vn.edu.uit.se214.health.knowledge.ingest.IngestSource.Batch;
import vn.edu.uit.se214.health.knowledge.ingest.IngestSource.ChunkRow;
import vn.edu.uit.se214.health.knowledge.ingest.KnowledgeIngestRepository.Outcome;

/**
 * SCRUM-48: loads the M7 articles and chunks into knowledge.* in one transaction, so a failed run leaves
 * the database as it was. Running it again with the same files changes nothing.
 */
public class KnowledgeIngest {
  /** Counts only: the report never carries content. */
  public record Report(
      Map<Outcome, Integer> articles, Map<Outcome, Integer> chunks, int retiredChunks, int skippedOutsideRag) {}

  private final IngestSource source;
  private final KnowledgeIngestRepository repository;
  private final TransactionOperations transactions;

  KnowledgeIngest(IngestSource source, KnowledgeIngestRepository repository, TransactionOperations transactions) {
    this.source = source;
    this.repository = repository;
    this.transactions = transactions;
  }

  public Report run(Path normalizedDir, Path translatedDir) {
    requireDir(normalizedDir, "normalized-dir");
    requireDir(translatedDir, "translated-dir");
    Batch batch = source.read(normalizedDir, translatedDir);
    validate(batch);
    return transactions.execute(status -> write(batch));
  }

  private Report write(Batch batch) {
    Map<Outcome, Integer> articles = counter();
    Map<Outcome, Integer> chunks = counter();
    for (ArticleRow a : batch.articles()) articles.merge(repository.upsertArticle(a), 1, Integer::sum);
    for (ChunkRow c : batch.chunks()) chunks.merge(repository.upsertChunk(c), 1, Integer::sum);
    int retired = repository.retireMissing(
        batch.articles().stream().map(ArticleRow::id).toList(), batch.chunks().stream().map(ChunkRow::id).toList());
    return new Report(articles, chunks, retired, batch.skippedOutsideRag());
  }

  /** Every chunk belongs to a loaded article, and no id or chunk key appears twice. */
  private static void validate(Batch batch) {
    Set<UUID> articles = new HashSet<>();
    for (ArticleRow a : batch.articles())
      if (!articles.add(a.id())) throw new IngestException("article " + a.id() + " appears twice");
    Set<UUID> ids = new HashSet<>();
    Set<String> keys = new HashSet<>();
    for (ChunkRow c : batch.chunks()) {
      if (!articles.contains(c.articleId()))
        throw new IngestException("chunk " + c.id() + " points to an article that is not loaded");
      if (!ids.add(c.id()) || !keys.add(c.chunkKey()))
        throw new IngestException("chunk " + c.id() + " appears twice");
    }
  }

  private static void requireDir(Path dir, String name) {
    if (dir == null || !Files.isDirectory(dir))
      throw new IngestException("app.knowledge.ingest." + name + " is not a folder");
  }

  private static Map<Outcome, Integer> counter() {
    Map<Outcome, Integer> map = new EnumMap<>(Outcome.class);
    for (Outcome o : Outcome.values()) map.put(o, 0);
    return map;
  }

  static List<String> describe(Report r) {
    return List.of(
        "articles " + r.articles(), "chunks " + r.chunks(), "retired chunks " + r.retiredChunks(),
        "skipped outside RAG corpus " + r.skippedOutsideRag());
  }
}
