package knowledge.api;

import shared.domain.SourceReference;

public record ArticleSummary(
    String id, String title, String topic, SourceReference source, String updatedAt) {}
