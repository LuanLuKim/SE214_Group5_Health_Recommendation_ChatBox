package vn.edu.uit.se214.health.knowledge.review;

/**
 * UC-17 is one use case owned by M2, with one shared review queue, one decision record
 * (knowledge.review_events), one API (/api/v1/knowledge/reviews) and one "Quản trị nội dung" screen.
 *
 * <p>Each module that owns reviewable content implements this interface as a Spring bean:
 *
 * <ul>
 *   <li>M1 (part M1 of UC-17, input review): {@code RULE} and M7 chunks — approve a rule for the
 *       Safety Gate, set {@code rag_eligible = true} on a chunk.
 *   <li>M2 (part M2 of UC-17, output review): {@code ARTICLE}, {@code TEMPLATE}, {@code FEEDBACK} —
 *       publish content for lookup, record agent result checks and feedback handling.
 * </ul>
 *
 * The queue calls {@link #apply} after storing the decision. Modules never update each other's
 * tables directly. Agree on changes to this contract in both UC-17 tasks before coding.
 */
public interface ReviewSubjectHandler {
  ReviewSubjectType subjectType();

  /** Apply the decision to the module's own data (status change, activation, publication). */
  void apply(ReviewDecision decision);
}
