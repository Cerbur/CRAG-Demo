package com.crag.demo.dto.result;

/**
 * 统一 RESTful 响应包装类 —— 所有 Controller 方法的返回类型.
 *
 * 包含三个字段：success（是否成功）、code（业务状态码）、result（泛型 payload）。
 * 仅通过静态工厂方法构造，禁止直接调用构造器。
 *
 * @param <T> result 字段的类型
 * @since 2026-06-13
 */
public class Response<T> {

    /** 是否成功，true=业务处理成功，false=业务处理失败 */
    private final boolean success;

    /** 业务状态码，取自 ResponseCode 枚举 */
    private final int code;

    /** 业务结果 payload，成功时为返回数据，失败时为 null 或错误详情 */
    private final T result;

    private Response(boolean success, int code, T result) {
        this.success = success;
        this.code = code;
        this.result = result;
    }

    /**
     * 创建成功响应.
     *
     * @param result 业务结果 payload，可为 null
     * @param <T>    result 类型
     * @return success=true、code=0、result 为传入值的 Response
     */
    public static <T> Response<T> success(T result) {
        return new Response<>(true, ResponseCode.SUCCESS.getCode(), result);
    }

    /**
     * 创建无 payload 的错误响应.
     *
     * @param code 错误响应码
     * @param <T>  result 类型
     * @return success=false、code=指定值、result=null 的 Response
     */
    public static <T> Response<T> error(ResponseCode code) {
        return new Response<>(false, code.getCode(), null);
    }

    /**
     * 创建带 payload 的错误响应（如错误详情）.
     *
     * @param code   错误响应码
     * @param result 错误详情 payload
     * @param <T>    result 类型
     * @return success=false、code=指定值、result 为传入值的 Response
     */
    public static <T> Response<T> error(ResponseCode code, T result) {
        return new Response<>(false, code.getCode(), result);
    }

    // --- Getters（Jackson 序列化依赖） ---

    /** @return 是否成功 */
    public boolean isSuccess() {
        return success;
    }

    /** @return 业务状态码 */
    public int getCode() {
        return code;
    }

    /** @return 业务结果 payload */
    public T getResult() {
        return result;
    }
}
