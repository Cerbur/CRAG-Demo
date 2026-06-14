package ai.cerbur.crag.storage.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * ChunkStatus 枚举 ↔ SMALLINT 转换器.
 *
 * 数据库存 SMALLINT（0-4），Java 侧用 ChunkStatus 枚举。
 * 避免 @Enumerated(ORDINAL) 的枚举顺序耦合风险。
 *
 * @since 2026-06-10
 */
@Converter(autoApply = true)
public class ChunkStatusConverter implements AttributeConverter<ChunkStatus, Integer> {

    @Override
    public Integer convertToDatabaseColumn(ChunkStatus status) {
        if (status == null) {
            return ChunkStatus.INIT.getCode();
        }
        return status.getCode();
    }

    @Override
    public ChunkStatus convertToEntityAttribute(Integer code) {
        if (code == null) {
            return ChunkStatus.INIT;
        }
        return ChunkStatus.fromCode(code);
    }
}
