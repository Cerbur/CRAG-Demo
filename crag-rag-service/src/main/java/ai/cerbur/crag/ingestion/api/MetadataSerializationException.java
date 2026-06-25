package ai.cerbur.crag.ingestion.api;

import tools.jackson.core.JacksonException;

/**
 * Thrown when chunk metadata JSON serialization fails.
 *
 * <p>Preserves the underlying Jackson cause for diagnostics. The global exception handler maps this
 * to the existing {@code 50001 INTERNAL_ERROR} code — no new HTTP business code is introduced.
 *
 * @since 2026-06-20
 */
public class MetadataSerializationException extends RuntimeException {

  public MetadataSerializationException(String message, JacksonException cause) {
    super(message, cause);
  }
}
