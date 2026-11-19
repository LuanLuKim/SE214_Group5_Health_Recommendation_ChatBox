package vn.edu.uit.se214.health.knowledge.authoring;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import vn.edu.uit.se214.health.shared.web.ApiException;

/**
 * UC-15 for one kind of draft: create, list, read, edit, delete and submit for review (UC-17). Nothing here
 * approves or publishes content: a new or edited draft has no verification date, and submission only moves
 * DRAFT/RETURNED to PENDING_REVIEW. The checks run in this order: body shape, existence, state, content.
 */
public class DraftService<I extends DraftInput<I>, D extends Draft<I>> {
  private final DraftStore<I, D> store;
  private final ObjectMapper json;
  private final ObjectMapper strictJson;
  private final Class<I> inputType;
  private final String notFoundMessage;
  private final Supplier<ApiException> deleteBlocked;

  /**
   * @param deleteBlocked error when an editable row could not be deleted (templates: a plan uses it)
   */
  public DraftService(DraftStore<I, D> store, ObjectMapper json, Class<I> inputType, String notFoundMessage,
      Supplier<ApiException> deleteBlocked) {
    this.store = store;
    this.json = json;
    // Same as z.strictObject: unknown fields (reviewStatus, verifiedAt, revision...) are rejected, 1.5 is not 1.
    this.strictJson = json.copy()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    this.inputType = inputType;
    this.notFoundMessage = notFoundMessage;
    this.deleteBlocked = deleteBlocked;
  }

  public D create(JsonNode body, UUID actor) {
    I input = parse(body).validated();
    try {
      return store.create(input, actor);
    } catch (DataIntegrityViolationException e) {
      throw DraftFields.invalid(); // a value the schema refuses, e.g. a goal M6 does not know
    }
  }

  public List<D> list() {
    return store.list();
  }

  public D get(UUID id) {
    return store.get(id).orElseThrow(this::missing);
  }

  /**
   * PATCH: the fields sent replace the stored ones, then the whole draft is validated again (a template
   * stored before UC-15 must be completed first). The write is refused if the draft changed meanwhile.
   */
  public D update(UUID id, JsonNode patch, UUID actor) {
    if (patch == null || !patch.isObject() || patch.isEmpty()) throw DraftFields.invalid();
    D current = get(id);
    if (!current.editable()) throw DraftFields.notEditable();
    ObjectNode merged = json.valueToTree(current.input());
    patch.fields().forEachRemaining(field -> merged.set(field.getKey(), field.getValue()));
    I input = parse(merged).validated();
    try {
      return store.update(id, input, actor, current.revision()).orElseThrow(() -> conflict(id, false));
    } catch (DataIntegrityViolationException e) {
      throw DraftFields.invalid();
    }
  }

  public void delete(UUID id) {
    try {
      if (store.delete(id)) return;
    } catch (DataIntegrityViolationException e) {
      throw deleteBlocked.get(); // a plan started using the template between the check and the delete
    }
    D current = get(id);
    if (!current.editable()) throw DraftFields.notEditable();
    throw deleteBlocked.get();
  }

  /** Validates the stored content and hands exactly that revision to the review queue. */
  public D submit(UUID id, JsonNode body, UUID actor) {
    if (body != null && !body.isNull() && (!body.isObject() || !body.isEmpty())) throw DraftFields.invalid();
    D current = get(id);
    if (!current.editable()) throw DraftFields.notSubmittable();
    current.input().validated();
    return store.submit(id, actor, current.revision()).orElseThrow(() -> conflict(id, true));
  }

  private I parse(JsonNode body) {
    if (body == null || !body.isObject()) throw DraftFields.invalid();
    try {
      return strictJson.treeToValue(body, inputType);
    } catch (Exception e) {
      throw DraftFields.invalid();
    }
  }

  /** A guarded write changed nothing: the draft is gone, locked, or was edited since it was read. */
  private ApiException conflict(UUID id, boolean submitting) {
    return store.get(id)
        .map(now -> now.editable()
            ? DraftFields.changed()
            : submitting ? DraftFields.notSubmittable() : DraftFields.notEditable())
        .orElseGet(this::missing);
  }

  private ApiException missing() {
    return DraftFields.notFound(notFoundMessage);
  }
}
