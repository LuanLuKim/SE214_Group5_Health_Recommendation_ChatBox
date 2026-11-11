package vn.edu.uit.se214.health.shared.domain;

/** Where a piece of approved content comes from. {@code verifiedAt} is an ISO-8601 instant. */
public record SourceReference(String id, String name, String url, String verifiedAt) {}
