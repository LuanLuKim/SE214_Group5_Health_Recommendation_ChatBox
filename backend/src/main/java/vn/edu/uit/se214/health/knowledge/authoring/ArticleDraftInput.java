package vn.edu.uit.se214.health.knowledge.authoring;

/**
 * UC-15 body of POST /api/v1/knowledge/articles (PATCH sends a subset). Review state, verification date,
 * revision and editors are set by the server, so they are not fields here: a body that sends them is 400.
 */
public record ArticleDraftInput(
    String title,
    String topic,
    String content,
    String sourceName,
    String sourceUrl,
    String sourceUpdatedAt,
    String copyrightNotes)
    implements DraftInput<ArticleDraftInput> {

  @Override
  public ArticleDraftInput validated() {
    return new ArticleDraftInput(
        DraftFields.text(title, 300),
        DraftFields.text(topic, 200),
        DraftFields.text(content, 30_000),
        DraftFields.text(sourceName, 200),
        DraftFields.url(sourceUrl),
        DraftFields.sourceDate(sourceUpdatedAt),
        DraftFields.optionalText(copyrightNotes, 3000));
  }
}
