package ai.cerbur.crag.id.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import ai.cerbur.crag.id.api.CragIdParser;
import ai.cerbur.crag.id.api.IdEntityType;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SnowflakeLayout")
class SnowflakeLayoutTest {

  private final SnowflakeLayout layout = new SnowflakeLayout();

  @Test
  @DisplayName("encode and decode keeps entity, timestamp, worker and sequence")
  void encodeAndDecodeKeepsParts() {
    long id =
        layout.encode(IdEntityType.LEGACY_DOCUMENT, SnowflakeLayout.EPOCH_MILLIS + 123L, 3, 17);

    CragIdParser.CragIdParts parts = layout.decode(id);
    assertThat(parts.entityType()).isEqualTo(IdEntityType.LEGACY_DOCUMENT);
    assertThat(parts.timestamp())
        .isEqualTo(Instant.ofEpochMilli(SnowflakeLayout.EPOCH_MILLIS + 123L));
    assertThat(parts.workerId()).isEqualTo(3);
    assertThat(parts.sequence()).isEqualTo(17);
  }

  @Test
  @DisplayName("CHUNK encodes a different high bits than LEGACY_DOCUMENT")
  void chunkHasDifferentHighBitsThanLegacyDocument() {
    long docId =
        layout.encode(IdEntityType.LEGACY_DOCUMENT, SnowflakeLayout.EPOCH_MILLIS + 1L, 0, 0);
    long chunkId = layout.encode(IdEntityType.CHUNK, SnowflakeLayout.EPOCH_MILLIS + 1L, 0, 0);

    assertThat(docId).isNotEqualTo(chunkId);

    CragIdParser.CragIdParts docParts = layout.decode(docId);
    CragIdParser.CragIdParts chunkParts = layout.decode(chunkId);
    assertThat(docParts.entityType()).isEqualTo(IdEntityType.LEGACY_DOCUMENT);
    assertThat(chunkParts.entityType()).isEqualTo(IdEntityType.CHUNK);
  }

  @Nested
  @DisplayName("encode validates arguments")
  class EncodeValidation {

    @Test
    @DisplayName("rejects worker >= 16")
    void rejectsWorkerGreaterEqual16() {
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  layout.encode(IdEntityType.LEGACY_DOCUMENT, SnowflakeLayout.EPOCH_MILLIS, 16, 0));
    }

    @Test
    @DisplayName("rejects negative worker")
    void rejectsNegativeWorker() {
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  layout.encode(IdEntityType.LEGACY_DOCUMENT, SnowflakeLayout.EPOCH_MILLIS, -1, 0));
    }

    @Test
    @DisplayName("rejects sequence >= 1024")
    void rejectsSequenceGreaterEqual1024() {
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  layout.encode(
                      IdEntityType.LEGACY_DOCUMENT, SnowflakeLayout.EPOCH_MILLIS, 0, 1024));
    }

    @Test
    @DisplayName("rejects negative sequence")
    void rejectsNegativeSequence() {
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  layout.encode(IdEntityType.LEGACY_DOCUMENT, SnowflakeLayout.EPOCH_MILLIS, 0, -1));
    }

    @Test
    @DisplayName("rejects timestamp before epoch")
    void rejectsTimestampBeforeEpoch() {
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  layout.encode(
                      IdEntityType.LEGACY_DOCUMENT, SnowflakeLayout.EPOCH_MILLIS - 1, 0, 0));
    }
  }

  @Test
  @DisplayName("max timestamp fits in 41 bits")
  void maxTimestampFits() {
    long maxTimestamp = SnowflakeLayout.EPOCH_MILLIS + ((1L << 41) - 1);
    long id = layout.encode(IdEntityType.CHUNK, maxTimestamp, 15, 1023);

    CragIdParser.CragIdParts parts = layout.decode(id);
    assertThat(parts.timestamp()).isEqualTo(Instant.ofEpochMilli(maxTimestamp));
    assertThat(parts.workerId()).isEqualTo(15);
    assertThat(parts.sequence()).isEqualTo(1023);
  }
}
