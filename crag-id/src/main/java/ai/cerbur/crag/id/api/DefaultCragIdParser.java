package ai.cerbur.crag.id.api;

import ai.cerbur.crag.id.internal.SnowflakeLayout;

/**
 * Default {@link CragIdParser} implementation that delegates bit-level decoding to {@link
 * SnowflakeLayout} and adds decimal-string parsing and entity-type validation.
 */
public final class DefaultCragIdParser implements CragIdParser {

  private final SnowflakeLayout layout;

  public DefaultCragIdParser(SnowflakeLayout layout) {
    this.layout = layout;
  }

  @Override
  public CragIdParts parse(long id) {
    return layout.decode(id);
  }

  @Override
  public long parseDecimal(String value, IdEntityType expectedEntityType) {
    long id;
    try {
      id = Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new InvalidCragIdException(
          "Invalid ID: \"" + value + "\" is not a valid decimal number", e);
    }
    if (id < 0) {
      throw new InvalidCragIdException("Invalid ID: " + value + " is negative");
    }
    requireEntityType(id, expectedEntityType);
    return id;
  }

  @Override
  public void requireEntityType(long id, IdEntityType expectedEntityType) {
    CragIdParts parts = layout.decode(id);
    if (parts.entityType() != expectedEntityType) {
      throw new InvalidCragIdException(
          "Entity type mismatch: expected "
              + expectedEntityType
              + " but got "
              + parts.entityType());
    }
  }
}
