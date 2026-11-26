package knowledge.ingest;

/** The input files are not what the loader expects; nothing is written. Messages never quote content. */
class IngestException extends RuntimeException {
  IngestException(String message) {
    super(message);
  }
}
