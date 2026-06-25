package ai.cerbur.crag.knowledge.controller.smoke.dto;

/** smoke 文档事件发布诊断视图。 */
public record DocumentEventSmokeResponse(String docId, String outboxStatus, int attemptCount) {}
