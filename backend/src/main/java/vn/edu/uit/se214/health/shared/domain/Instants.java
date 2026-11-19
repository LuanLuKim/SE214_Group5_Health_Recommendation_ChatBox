package vn.edu.uit.se214.health.shared.domain;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** ISO-8601 UTC with milliseconds, the same shape as JavaScript's toISOString(). */
public final class Instants {
  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private Instants() {}

  public static String iso(OffsetDateTime value) {
    return value == null ? null : ISO.format(value.toInstant());
  }
}
