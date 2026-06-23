package ai.cerbur.crag.id.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.id.internal.SnowflakeLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CragIdParser")
class CragIdParserTest {

  private final SnowflakeLayout layout = new SnowflakeLayout();
  private final CragIdParser parser = new DefaultCragIdParser(layout);

  @Nested
  @DisplayName("parseDecimal")
  class ParseDecimal {

    @Test
    @DisplayName("parses valid decimal string")
    void parsesValidDecimalString() {
      long originalId =
          layout.encode(IdEntityType.LEGACY_DOCUMENT, SnowflakeLayout.EPOCH_MILLIS + 100L, 5, 42);

      long parsed = parser.parseDecimal(Long.toString(originalId), IdEntityType.LEGACY_DOCUMENT);

      assertThat(parsed).isEqualTo(originalId);
    }

    @Test
    @DisplayName("rejects non-numeric string")
    void rejectsNonNumericString() {
      assertThatThrownBy(() -> parser.parseDecimal("abc", IdEntityType.LEGACY_DOCUMENT))
          .isInstanceOf(InvalidCragIdException.class);
    }

    @Test
    @DisplayName("rejects negative string")
    void rejectsNegativeString() {
      assertThatThrownBy(() -> parser.parseDecimal("-1", IdEntityType.LEGACY_DOCUMENT))
          .isInstanceOf(InvalidCragIdException.class);
    }

    @Test
    @DisplayName("rejects empty string")
    void rejectsEmptyString() {
      assertThatThrownBy(() -> parser.parseDecimal("", IdEntityType.LEGACY_DOCUMENT))
          .isInstanceOf(InvalidCragIdException.class);
    }

    @Test
    @DisplayName("rejects CHUNK id when LEGACY_DOCUMENT expected")
    void rejectsEntityTypeMismatch() {
      long chunkId = layout.encode(IdEntityType.CHUNK, SnowflakeLayout.EPOCH_MILLIS + 100L, 0, 0);

      assertThatThrownBy(
              () -> parser.parseDecimal(Long.toString(chunkId), IdEntityType.LEGACY_DOCUMENT))
          .isInstanceOf(InvalidCragIdException.class);
    }
  }

  @Nested
  @DisplayName("parse(long)")
  class ParseLong {

    @Test
    @DisplayName("parses raw long id to parts")
    void parsesRawLongToParts() {
      long id = layout.encode(IdEntityType.CHUNK, SnowflakeLayout.EPOCH_MILLIS + 500L, 7, 99);

      CragIdParser.CragIdParts parts = parser.parse(id);

      assertThat(parts.entityType()).isEqualTo(IdEntityType.CHUNK);
      assertThat(parts.workerId()).isEqualTo(7);
      assertThat(parts.sequence()).isEqualTo(99);
    }
  }

  @Nested
  @DisplayName("requireEntityType")
  class RequireEntityType {

    @Test
    @DisplayName("passes when entity type matches")
    void passesWhenEntityTypeMatches() {
      long docId =
          layout.encode(IdEntityType.LEGACY_DOCUMENT, SnowflakeLayout.EPOCH_MILLIS + 100L, 0, 0);

      parser.requireEntityType(docId, IdEntityType.LEGACY_DOCUMENT);
    }

    @Test
    @DisplayName("throws when entity type mismatches")
    void throwsWhenEntityTypeMismatches() {
      long chunkId = layout.encode(IdEntityType.CHUNK, SnowflakeLayout.EPOCH_MILLIS + 100L, 0, 0);

      assertThatThrownBy(() -> parser.requireEntityType(chunkId, IdEntityType.LEGACY_DOCUMENT))
          .isInstanceOf(InvalidCragIdException.class);
    }
  }
}
