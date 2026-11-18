package vn.edu.uit.se214.health.knowledge.authoring;

import vn.edu.uit.se214.health.shared.domain.ReviewStatus;

/** A stored article or template draft as the authoring API returns it. */
public interface Draft<I> {
  String id();

  ReviewStatus reviewStatus();

  int revision();

  /** The editable fields, as a body that would recreate them (used to merge a PATCH). */
  I input();

  default boolean editable() {
    return reviewStatus() == ReviewStatus.DRAFT || reviewStatus() == ReviewStatus.RETURNED;
  }
}
