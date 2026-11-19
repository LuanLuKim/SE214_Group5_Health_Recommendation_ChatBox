package vn.edu.uit.se214.health.knowledge.api;

import java.util.List;
import vn.edu.uit.se214.health.shared.domain.SourceReference;

public record FacilitySummary(
    String id,
    String name,
    List<String> specialties,
    String province,
    String address,
    String officialUrl,
    SourceReference source) {}
