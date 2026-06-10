package com.crag.demo.service;

import org.springframework.stereotype.Service;

/**
 * 用户查询服务 —— 编排混合检索流水线（Dense + Sparse → RRF → Rerank → LLM 生成）.
 *
 * 遵循奥卡姆剃刀：当前只有一个实现，不做 Interface/Impl 分离.
 *
 * @since 2026-06-10
 */
@Service
public class UserQueryService {

    /**
     * 执行用户查询（骨架，plan_2 实现完整流水线）.
     *
     * @param question 用户问题文本
     * @return 空字符串
     */
    public String answer(String question) {
        return "";
    }
}
