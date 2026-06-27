package ai.cerbur.crag.id.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Locks the entity type code assignments embedded in Snowflake IDs.
 *
 * <p>Codes are a cross-module contract: existing values must never shift, new values must be
 * unique, and the layout reserves the high 8 bits. plan_20 adds the Access codes 3–9 without
 * disturbing 1–2.
 */
class IdEntityTypeTest {

  @Test
  @DisplayName("既有 code 1–2 保持稳定，Access code 3–9 固定")
  void codesAreStableAndAssigned() {
    assertEquals(1, IdEntityType.LEGACY_DOCUMENT.code(), "LEGACY_DOCUMENT must stay code 1");
    assertEquals(2, IdEntityType.CHUNK.code(), "CHUNK must stay code 2");
    assertEquals(3, IdEntityType.USER.code());
    assertEquals(4, IdEntityType.LOGIN_ACCOUNT.code());
    assertEquals(5, IdEntityType.TENANT.code());
    assertEquals(6, IdEntityType.TENANT_MEMBERSHIP.code());
    assertEquals(7, IdEntityType.REFRESH_SESSION.code());
    assertEquals(8, IdEntityType.API_KEY.code());
    assertEquals(9, IdEntityType.ACCESS_EVENT.code());
  }

  @Test
  @DisplayName("所有 code 互不相同且落在 8 位范围内")
  void codesAreUniqueAndByteRanged() {
    Set<Integer> seen = new HashSet<>();
    for (IdEntityType type : IdEntityType.values()) {
      int code = type.code();
      assertTrue(code >= 1 && code <= 255, "code out of 8-bit range: " + type);
      assertTrue(seen.add(code), "duplicate code " + code + " for " + type);
    }
  }

  @Test
  @DisplayName("fromCode 双向往返解析")
  void fromCodeRoundTrip() {
    for (IdEntityType type : IdEntityType.values()) {
      assertSame(type, IdEntityType.fromCode(type.code()));
    }
  }

  @Test
  @DisplayName("fromCode 拒绝未知 code")
  void fromCodeRejectsUnknown() {
    assertThrows(IllegalArgumentException.class, () -> IdEntityType.fromCode(250));
  }
}
