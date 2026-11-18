package vn.edu.uit.se214.health.knowledge.authoring;

import com.fasterxml.jackson.annotation.JsonIgnore;
import vn.edu.uit.se214.health.shared.domain.ReviewStatus;

/** UC-15 article as the authoring API returns it, every state, with editorial metadata (never public). */
public record ArticleDraft(
    String id,
    String title,
    String topic,
    String content,
    String sourceName,
    String sourceUrl,
    String sourceUpdatedAt,
    String copyrightNotes,
    String verifiedAt,
    ReviewStatus reviewStatus,
    String createdBy,
    String updatedBy,
    String createdAt,
    String updatedAt,
    int revision,
    String submittedAt,
    String submittedBy)
    implements Draft<ArticleDraftInput> {

  @Override
  @JsonIgnore
  public ArticleDraftInput input() {
    return new ArticleDraftInput(title, topic, content, sourceName, sourceUrl, sourceUpdatedAt, copyrightNotes);
  }

  @Override
  @JsonIgnore
  public boolean editable() {
    return Draft.super.editable();
  }
}
