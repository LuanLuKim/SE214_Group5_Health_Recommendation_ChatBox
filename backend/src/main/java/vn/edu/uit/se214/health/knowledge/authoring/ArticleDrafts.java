package vn.edu.uit.se214.health.knowledge.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;

/** UC-15 article drafts (knowledge.articles). */
public class ArticleDrafts extends DraftService<ArticleDraftInput, ArticleDraft> {
  public ArticleDrafts(DraftStore<ArticleDraftInput, ArticleDraft> store, ObjectMapper json) {
    // Deleting an editable article only fails when it changed between the delete and the read.
    super(store, json, ArticleDraftInput.class, "Không tìm thấy bài viết.", DraftFields::changed);
  }
}
