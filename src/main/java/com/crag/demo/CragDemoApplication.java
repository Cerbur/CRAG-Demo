package com.crag.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CRAG-Demo 启动类 —— 基于 RAG 的问答机器人后端服务入口.
 *
 * 技术栈: Java 21 + Spring Boot 3.x + PostgreSQL + pgvector.
 * 启动后暴露 REST API 在 8080 端口.
 *
 * @since 2026-06-10
 */
@SpringBootApplication
public class CragDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CragDemoApplication.class, args);
    }
}
