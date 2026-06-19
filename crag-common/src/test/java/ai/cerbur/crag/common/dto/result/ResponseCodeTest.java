package ai.cerbur.crag.common.dto.result;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 验证 ResponseCode 每个枚举值的业务码、默认消息与 HTTP 状态。
 *
 * <p>本测试先于实现存在——当前旧 ResponseCode 仅有 SUCCESS/BAD_REQUEST/NOT_FOUND/INTERNAL_ERROR 四个值， 缺少
 * VALIDATION_ERROR/INVALID_ARGUMENT 且不持有 HttpStatus。实现完成后所有断言通过。
 */
class ResponseCodeTest {

  @Test
  void success_hasCorrectMetadata() {
    assertAll(
        () -> assertEquals(0, ResponseCode.SUCCESS.getCode(), "SUCCESS code"),
        () -> assertEquals("Success", ResponseCode.SUCCESS.getDefaultMessage(), "SUCCESS message"),
        () ->
            assertEquals(
                HttpStatus.OK, ResponseCode.SUCCESS.getHttpStatus(), "SUCCESS http status"));
  }

  @Test
  void validationError_hasCorrectMetadata() {
    assertAll(
        () -> assertEquals(40001, ResponseCode.VALIDATION_ERROR.getCode(), "VALIDATION_ERROR code"),
        () ->
            assertEquals(
                "Validation failed",
                ResponseCode.VALIDATION_ERROR.getDefaultMessage(),
                "VALIDATION_ERROR message"),
        () ->
            assertEquals(
                HttpStatus.BAD_REQUEST,
                ResponseCode.VALIDATION_ERROR.getHttpStatus(),
                "VALIDATION_ERROR http status"));
  }

  @Test
  void invalidArgument_hasCorrectMetadata() {
    assertAll(
        () -> assertEquals(40002, ResponseCode.INVALID_ARGUMENT.getCode(), "INVALID_ARGUMENT code"),
        () ->
            assertEquals(
                "Invalid argument",
                ResponseCode.INVALID_ARGUMENT.getDefaultMessage(),
                "INVALID_ARGUMENT message"),
        () ->
            assertEquals(
                HttpStatus.BAD_REQUEST,
                ResponseCode.INVALID_ARGUMENT.getHttpStatus(),
                "INVALID_ARGUMENT http status"));
  }

  @Test
  void notFound_hasCorrectMetadata() {
    assertAll(
        () -> assertEquals(40401, ResponseCode.NOT_FOUND.getCode(), "NOT_FOUND code"),
        () ->
            assertEquals(
                "Resource not found",
                ResponseCode.NOT_FOUND.getDefaultMessage(),
                "NOT_FOUND message"),
        () ->
            assertEquals(
                HttpStatus.NOT_FOUND,
                ResponseCode.NOT_FOUND.getHttpStatus(),
                "NOT_FOUND http status"));
  }

  @Test
  void internalError_hasCorrectMetadata() {
    assertAll(
        () -> assertEquals(50001, ResponseCode.INTERNAL_ERROR.getCode(), "INTERNAL_ERROR code"),
        () ->
            assertEquals(
                "Internal server error",
                ResponseCode.INTERNAL_ERROR.getDefaultMessage(),
                "INTERNAL_ERROR message"),
        () ->
            assertEquals(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ResponseCode.INTERNAL_ERROR.getHttpStatus(),
                "INTERNAL_ERROR http status"));
  }

  @Test
  void badRequest_shouldNotExist() {
    // 验证 BAD_REQUEST 已被移除
    ResponseCode[] values = ResponseCode.values();
    for (ResponseCode rc : values) {
      if (rc.name().equals("BAD_REQUEST")) {
        throw new AssertionError("BAD_REQUEST should not exist in ResponseCode");
      }
    }
  }

  @Test
  void allCodesAreUnique() {
    ResponseCode[] values = ResponseCode.values();
    long distinctCodes =
        java.util.Arrays.stream(values).map(ResponseCode::getCode).distinct().count();
    assertEquals(values.length, distinctCodes, "All ResponseCode values must have unique codes");
  }
}
