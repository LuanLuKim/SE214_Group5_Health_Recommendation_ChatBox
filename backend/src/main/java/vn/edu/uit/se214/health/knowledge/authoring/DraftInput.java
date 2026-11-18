package vn.edu.uit.se214.health.knowledge.authoring;

/** An authoring body: {@link #validated()} returns a trimmed copy or throws 400 INVALID_INPUT. */
public interface DraftInput<I> {
  I validated();
}
