package knowledge.api;

import java.util.List;
import shared.domain.SourceReference;

public record FacilitySummary(
    String id,
    String name,
    List<String> specialties,
    String province,
    String address,
    String officialUrl,
    SourceReference source) {}
