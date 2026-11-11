package vn.edu.uit.se214.health.system;

import java.util.List;

/** Mirrors ModuleDescriptor in packages/contracts so the frontend can parse it unchanged. */
public record ModuleDescriptor(
    String id,
    int number,
    String title,
    String description,
    List<String> useCases,
    String apiPrefix,
    String status) {}
