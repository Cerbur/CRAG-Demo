package ai.cerbur.crag.id.api;

/**
 * Public interface for generating service-domain-unique Snowflake IDs.
 *
 * <p>Each call to {@link #nextId(IdEntityType)} produces a 64-bit ID whose high bits encode the
 * entity type. Implementations are responsible for worker lease coordination, clock monitoring, and
 * sequence management.
 */
public interface CragIdGenerator {

  /**
   * Generate the next unique ID for the given entity type.
   *
   * @param entityType the registered entity type (determines high-bit encoding)
   * @return a Snowflake ID unique within the service domain + entity type
   * @throws IllegalStateException if the generator is not ready (e.g. worker lease lost or clock
   *     rollback exceeded threshold)
   */
  long nextId(IdEntityType entityType);
}
