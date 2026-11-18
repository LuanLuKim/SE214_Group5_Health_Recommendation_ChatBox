package vn.edu.uit.se214.health.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Marks an audit case that still fails because its bug is open. The case is skipped by default so
 * CI stays green, and runs with {@code -Daudit.knownBugs=true} to reproduce the bug. Whoever fixes
 * the bug removes this annotation so the case becomes a regression test.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Tag("known-bug")
@EnabledIfSystemProperty(
    named = "audit.knownBugs",
    matches = "true",
    disabledReason = "Lỗi còn mở. Chạy với -Daudit.knownBugs=true để tái hiện.")
public @interface KnownBug {
  /** GitHub issue of the open bug, e.g. "#152". */
  String value();
}
