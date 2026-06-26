package ai.cerbur.crag.storage.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * IngestionJobStatus 枚举 ↔ SMALLINT 转换器（Plan 19）.
 *
 * <p>数据库存 SMALLINT（0-3），Java 侧用 IngestionJobStatus 枚举，避免 @Enumerated(ORDINAL) 的顺序耦合风险.
 */
@Converter(autoApply = true)
public class IngestionJobStatusConverter
    implements AttributeConverter<IngestionJobStatus, Integer> {

  @Override
  public Integer convertToDatabaseColumn(IngestionJobStatus status) {
    if (status == null) {
      return IngestionJobStatus.PENDING.getCode();
    }
    return status.getCode();
  }

  @Override
  public IngestionJobStatus convertToEntityAttribute(Integer code) {
    if (code == null) {
      return IngestionJobStatus.PENDING;
    }
    return IngestionJobStatus.fromCode(code);
  }
}
