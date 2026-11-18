package vn.edu.uit.se214.health.shared.domain;

/** Review lifecycle shared by articles, templates and rules (see the CHECK constraints in database/migrations). */
public enum ReviewStatus {
  DRAFT,
  PENDING_REVIEW,
  APPROVED,
  RETURNED,
  RETIRED
}
