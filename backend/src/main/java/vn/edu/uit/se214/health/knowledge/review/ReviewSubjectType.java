package vn.edu.uit.se214.health.knowledge.review;

/**
 * What can wait in the shared review queue (knowledge.review_events.subject_type).
 * Add a value together with a migration when a new kind is reviewed (e.g. M7 chunks).
 */
public enum ReviewSubjectType {
  ARTICLE,
  TEMPLATE,
  RULE,
  FEEDBACK
}
