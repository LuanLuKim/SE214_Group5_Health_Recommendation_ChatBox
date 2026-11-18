package vn.edu.uit.se214.health.knowledge.authoring;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of one kind of draft. Every write checks the state in the same statement: update and
 * submit only change a DRAFT/RETURNED row whose revision is still {@code revision}; delete only removes a
 * DRAFT/RETURNED row (and, for templates, one no plan uses). An empty result or {@code false} means the
 * row is missing, locked or changed: the service reads it again to tell which.
 */
public interface DraftStore<I, D extends Draft<I>> {
  D create(I input, UUID actor);

  List<D> list();

  Optional<D> get(UUID id);

  Optional<D> update(UUID id, I input, UUID actor, int revision);

  Optional<D> submit(UUID id, UUID actor, int revision);

  boolean delete(UUID id);
}
