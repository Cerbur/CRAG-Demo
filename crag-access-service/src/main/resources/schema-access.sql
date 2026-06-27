-- ============================================================
-- CRAG-Demo — Access Service 真实业务 DDL
-- Schema: access (currentSchema=access, owner crag_access)
-- 通过 Spring sql.init 在默认与 smoke profile 启动时执行；CREATE TABLE IF NOT EXISTS
-- 保证幂等，不清空共享表、不删除 volume。所有业务表包含 created_at / updated_at / version。
-- 主键使用 Snowflake long（由 CragIdGenerator 在 Service 层分配），非数据库 identity。
-- ============================================================

-- platform_user：永久用户身份，Nickname 为展示名，不参与登录。
CREATE TABLE IF NOT EXISTS platform_user (
    user_id     BIGINT NOT NULL PRIMARY KEY,
    nickname    VARCHAR(255) NOT NULL,
    status      VARCHAR(32) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    version     BIGINT NOT NULL DEFAULT 0
);

-- login_account：登录凭据。首期 account_type 固定 USERNAME；normalized_identifier 全局唯一。
CREATE TABLE IF NOT EXISTS login_account (
    account_id            BIGINT NOT NULL PRIMARY KEY,
    user_id               BIGINT NOT NULL,
    account_type          VARCHAR(32) NOT NULL,
    login_identifier      VARCHAR(255) NOT NULL,
    normalized_identifier VARCHAR(255) NOT NULL,
    -- Argon2id 编码串；禁止明文密码。
    credential_hash       VARCHAR(512) NOT NULL,
    status                VARCHAR(32) NOT NULL,
    created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    version               BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_login_account_norm
    ON login_account(account_type, normalized_identifier);
CREATE INDEX IF NOT EXISTS idx_login_account_user ON login_account(user_id);

-- tenant：租户。
CREATE TABLE IF NOT EXISTS tenant (
    tenant_id   BIGINT NOT NULL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    status      VARCHAR(32) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    version     BIGINT NOT NULL DEFAULT 0
);

-- tenant_membership：成员关系。(tenant_id, user_id) 唯一；REMOVED 行被重新加入时复用为 MEMBER。
CREATE TABLE IF NOT EXISTS tenant_membership (
    membership_id BIGINT NOT NULL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    user_id       BIGINT NOT NULL,
    role          VARCHAR(32) NOT NULL,
    status        VARCHAR(32) NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    version       BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_membership
    ON tenant_membership(tenant_id, user_id);
CREATE INDEX IF NOT EXISTS idx_tenant_membership_tenant ON tenant_membership(tenant_id);

-- refresh_session：每次 Refresh Token 签发一行。token_hmac 唯一定位，不保存原文。
CREATE TABLE IF NOT EXISTS refresh_session (
    session_id   BIGINT NOT NULL PRIMARY KEY,
    family_id    BIGINT NOT NULL,
    user_id      BIGINT NOT NULL,
    token_hmac   VARCHAR(128) NOT NULL,
    status       VARCHAR(32) NOT NULL,
    issued_at    TIMESTAMP NOT NULL,
    expires_at   TIMESTAMP NOT NULL,
    rotated_at   TIMESTAMP,
    revoked_at   TIMESTAMP,
    replaced_by  BIGINT,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    version      BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_refresh_session_hmac ON refresh_session(token_hmac);
CREATE INDEX IF NOT EXISTS idx_refresh_session_family ON refresh_session(family_id);
CREATE INDEX IF NOT EXISTS idx_refresh_session_user ON refresh_session(user_id);

-- api_key_scope：KnowledgeBase 最小授权投影。knowledge_base_id 即主键；BLOCKED 为终态。
CREATE TABLE IF NOT EXISTS api_key_scope (
    knowledge_base_id BIGINT NOT NULL PRIMARY KEY,
    tenant_id         BIGINT NOT NULL,
    status            VARCHAR(32) NOT NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    version           BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_api_key_scope_tenant ON api_key_scope(tenant_id);

-- api_key：单 Key 绑定单 KnowledgeBase。完整 Key 永不落库，只保存可检索前缀与秘密 HMAC。
CREATE TABLE IF NOT EXISTS api_key (
    api_key_id         BIGINT NOT NULL PRIMARY KEY,
    tenant_id          BIGINT NOT NULL,
    knowledge_base_id  BIGINT NOT NULL,
    name               VARCHAR(255) NOT NULL,
    key_prefix         VARCHAR(64) NOT NULL,
    secret_hmac        VARCHAR(128) NOT NULL,
    status             VARCHAR(32) NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    last_used_at       TIMESTAMP,
    expires_at         TIMESTAMP NOT NULL,
    disabled_at        TIMESTAMP,
    revoked_at         TIMESTAMP,
    rotated_from       BIGINT,
    created_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    version            BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_api_key_prefix ON api_key(key_prefix);
CREATE INDEX IF NOT EXISTS idx_api_key_kb ON api_key(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_api_key_tenant ON api_key(tenant_id);

-- ============================================================
-- 可靠事件本地表（crag-event 接入宿主，属 Access 本地 schema）。
-- Access 仅生产事件（plan_20 不实现消费者），因此只需要 outbox_event；
-- 列结构必须与 crag-event 的 JdbcOutboxEventDao 保持一致。event_id 由
-- CragIdGenerator(ACCESS_EVENT) 分配，不使用数据库序列。
-- ============================================================
CREATE TABLE IF NOT EXISTS outbox_event (
  event_id BIGINT NOT NULL PRIMARY KEY,
  event_type VARCHAR(64) NOT NULL,
  producer VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id BIGINT NOT NULL,
  operation_version BIGINT NOT NULL,
  payload_version INT NOT NULL,
  payload_json TEXT NOT NULL,
  trace_id VARCHAR(128) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  status VARCHAR(16) NOT NULL,
  next_attempt_at TIMESTAMP WITH TIME ZONE,
  attempt_count INT NOT NULL DEFAULT 0,
  last_error_code VARCHAR(48),
  last_error_message VARCHAR(512),
  published_at TIMESTAMP WITH TIME ZONE,
  version BIGINT NOT NULL,
  claimed_by VARCHAR(64),
  claimed_until TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
