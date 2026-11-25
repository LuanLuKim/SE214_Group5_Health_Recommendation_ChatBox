package knowledge.api;

import shared.domain.SourceReference;

public record ArticleDetail(
    String id, String title, String topic, String content, SourceReference source, String updatedAt) {}
