package vn.edu.uit.se214.health.knowledge.api;

import vn.edu.uit.se214.health.shared.domain.SourceReference;

public record ArticleDetail(
    String id, String title, String topic, String content, SourceReference source, String updatedAt) {}
