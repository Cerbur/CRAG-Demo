package ai.cerbur.crag.open.query.dto;

/**
 * Open Query 引用（plan_21/21.10）。
 *
 * <p>source 只暴露 reference / documentId / excerpt，不暴露 chunk id、分数、Prompt 或 Context。 excerpt 已做 500
 * Unicode 字符防御截断。
 */
public record CitationResponse(String reference, String documentId, String excerpt) {}
