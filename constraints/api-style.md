# CRAG-Demo HTTP API 约束

> 本文档是 Controller、HTTP DTO、统一响应、校验与异常映射的唯一维护入口。通用 Java 规则见 `constraints/code-style.md`。

---

## 一、统一响应与 HTTP 状态

### 必须

- 成功响应返回 `Response<T>`，类型位于 `ai.cerbur.crag.common.dto.result`。
- HTTP 状态码表达协议层结果，例如 400、404、409、500。
- `Response.code` 表达稳定业务错误码，不得替代 HTTP 状态码。
- 错误响应由 `GlobalExceptionHandler` 统一构造，可使用 `ResponseEntity<Response<?>>` 设置 HTTP 状态。
- Controller 禁止自行拼装错误响应，禁止捕获通用异常后转换。
- 禁止直接返回 `Map<String, Object>`、Entity、内部 BO 或 Retrieval 阶段结果。
- 错误响应禁止暴露堆栈、SQL、密钥或外部服务原始报错。

### 构造方式

- 使用 `Response.success(result)` 构造成功响应。
- 错误响应使用统一静态工厂或异常映射，禁止直接调用 `Response` 构造器。
- 业务码必须来自 `ResponseCode`，禁止裸整数字面量。

---

## 二、错误码

### 必须

- 一个错误码只表达一种稳定语义。
- 区分通用错误与业务错误，例如 `VALIDATION_ERROR`、`CHUNK_NOT_FOUND`、`VERSION_CONFLICT`。
- 禁止用宽泛的 `BAD_REQUEST` 覆盖所有客户端错误。
- `ResponseCode` 保存业务码、默认安全消息和对应 HTTP 状态。
- 返回消息保持安全稳定，诊断细节仅记录日志。

---

## 三、HTTP DTO

### 必须

- HTTP DTO 的所有者是 API 边界模块 `crag-api`。
- 禁止新增 `crag-admin` 模块或 `ai.cerbur.crag.admin` package；正式 HTTP 边界统一使用 `crag-api`。
- 按业务能力组织包，例如 `dto.rag`、`dto.query`，避免所有类型横向堆入单一 `request` 或 `response` 包。
- 请求与响应使用独立契约，不直接暴露 Entity、内部 BO 或阶段结果。
- DTO 与业务对象的转换发生在 API 边界，禁止下层模块依赖 HTTP DTO。
- 正式 API 契约独立成文件；Controller 内部 record 仅用于测试或真正私有且不构成契约的小型结构。

### 推荐

- 简单不可变 DTO 优先使用 Java `record`。

---

## 四、请求校验

### 必须

- 请求体封装为 DTO。
- 使用 `@Valid` 与 Jakarta Bean Validation 表达结构性校验。
- 校验失败由 `GlobalExceptionHandler` 转换为 `VALIDATION_ERROR` 和 HTTP 400。
- 业务规则校验放在对应业务边界，不把所有业务规则塞入 Controller。

---

## 五、异常映射

### 必须

- `GlobalExceptionHandler` 是 HTTP 异常到状态码和 `ResponseCode` 的统一映射边界。
- `IllegalArgumentException` 映射为明确的客户端错误，不默认吞并所有业务异常。
- 资源不存在、版本冲突、外部依赖失败和内部错误使用不同错误码与适当 HTTP 状态。
- 兜底异常记录一次完整堆栈，并返回安全的内部错误响应。
- Controller 方法不写用于响应转换的 `try/catch`。
