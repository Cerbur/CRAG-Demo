package ai.cerbur.crag.id.api;

/**
 * Central registry of entity type codes embedded in the high bits of every Snowflake ID.
 *
 * <p>Each entity type occupies 8 bits (0–255). Adding a new entity type here is a coordinated
 * change that affects all generators, parsers, and cross-module ID contracts.
 */
public enum IdEntityType {

  /** Current RAG AdminRag document. Will be superseded by {@code DOCUMENT} in Plan 17. */
  LEGACY_DOCUMENT(1),

  /** Chunk — parent and child alike. */
  CHUNK(2);

  private final int code;

  IdEntityType(int code) {
    this.code = code;
  }

  /** 8-bit entity type code embedded in Snowflake ID high bits. */
  public int code() {
    return code;
  }

  /** Resolve entity type from an 8-bit code. */
  public static IdEntityType fromCode(int code) {
    for (IdEntityType type : values()) {
      if (type.code == code) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unknown entity type code: " + code);
  }
}
