package com.crag.demo.dto.result;

/**
 * 统一响应码枚举 —— 所有控制器通过 Response 包装类返回，code 字段值均来自此枚举.
 *
 * @since 2026-06-13
 */
public enum ResponseCode {

    /** 通用成功. */
    SUCCESS(0),

    /** 客户端错误 —— 请求参数无效或缺失. */
    BAD_REQUEST(400),

    /** 服务端错误 —— 未预期的内部异常. */
    INTERNAL_ERROR(500);

    private final int code;

    ResponseCode(int code) {
        this.code = code;
    }

    /**
     * 序列化到 Response.code 字段的整型码.
     *
     * @return 整型响应码
     */
    public int getCode() {
        return code;
    }
}
