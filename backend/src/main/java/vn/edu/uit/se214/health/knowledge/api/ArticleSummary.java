package vn.edu.uit.se214.health.knowledge.api;

import vn.edu.uit.se214.health.shared.domain.SourceReference;

public record ArticleSummary(
    String id, String title, String topic, SourceReference source, String updatedAt) {}
