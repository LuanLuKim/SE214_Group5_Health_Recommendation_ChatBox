package vn.edu.uit.se214.health.knowledge.review;

import java.util.UUID;

/** One reviewer decision recorded by the shared queue before the owning module is notified. */
public record ReviewDecision(
    ReviewSubjectType subjectType, UUID subjectId, UUID reviewerId, Outcome outcome, String note) {

  public enum Outcome {
    APPROVED,
    RETURNED
  }
}
