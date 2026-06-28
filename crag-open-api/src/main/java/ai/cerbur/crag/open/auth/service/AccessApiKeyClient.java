package ai.cerbur.crag.open.auth.service;

import ai.cerbur.crag.contracts.access.v1.ApiKeyServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.AuthenticateApiKeyRequest;
import ai.cerbur.crag.contracts.access.v1.AuthenticatedApiKey;
import ai.cerbur.crag.open.authcache.CachedApiKey;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Access API Key 鉴权 gRPC 适配器（plan_21/21.10）。
 *
 * <p>封装 {@code AuthenticateApiKey} 调用，将稳定 gRPC Status 映射为 Open 业务异常。鉴权失败统一为 {@link
 * InvalidApiKeyException}（40102），不泄漏存在性；下游不可用/超时映射为 50301/50401。
 *
 * <p>完整 Key 只在此方法内存活，不写入日志、指标或异常；返回的 {@link CachedApiKey} 不含完整 Key。
 */
@Component
public class AccessApiKeyClient {

  private static final Logger log = LoggerFactory.getLogger(AccessApiKeyClient.class);

  private final ManagedChannel channel;
  private final ApiKeyServiceGrpc.ApiKeyServiceBlockingStub stub;
  private final long deadlineMillis;

  @Autowired
  public AccessApiKeyClient(
      @Qualifier("openAccessChannel") ManagedChannel channel,
      @Value("${crag.grpc.client.max-deadline-millis:10000}") long deadlineMillis) {
    this.channel = channel;
    this.stub = ApiKeyServiceGrpc.newBlockingStub(channel);
    this.deadlineMillis = deadlineMillis;
  }

  /**
   * 鉴权完整 API Key。
   *
   * @param completeKey 完整 Key，格式 {@code crag_<前缀>_<秘密>}
   * @return 鉴权结果（不含完整 Key）
   * @throws InvalidApiKeyException 鉴权失败（40102）
   * @throws DownstreamUnavailableException 下游不可用（50301）
   * @throws DownstreamTimeoutException 下游超时（50401）
   */
  public CachedApiKey authenticate(String completeKey) {
    try {
      AuthenticatedApiKey resp =
          stub()
              .authenticateApiKey(
                  AuthenticateApiKeyRequest.newBuilder().setApiKey(completeKey).build());
      return new CachedApiKey(
          parseLong(resp.getApiKeyId()),
          parseLong(resp.getTenantId()),
          parseLong(resp.getKnowledgeBaseId()),
          resp.getKeyVersion(),
          resp.getScopeVersion(),
          parseInstantMillis(resp.getExpiresAtEpochMillis()));
    } catch (StatusRuntimeException e) {
      throw mapStatus(e);
    }
  }

  // ---- helpers ----

  private ApiKeyServiceGrpc.ApiKeyServiceBlockingStub stub() {
    if (deadlineMillis > 0) {
      return stub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }
    return stub;
  }

  private static RuntimeException mapStatus(StatusRuntimeException e) {
    Status.Code code = e.getStatus().getCode();
    if (code == Status.Code.UNAUTHENTICATED
        || code == Status.Code.NOT_FOUND
        || code == Status.Code.INVALID_ARGUMENT) {
      log.debug("Access API Key 鉴权拒绝 — code={}", code);
      return new InvalidApiKeyException();
    }
    if (code == Status.Code.DEADLINE_EXCEEDED) {
      return new DownstreamTimeoutException();
    }
    log.warn("Access 下游调用失败 — code={} desc={}", code, e.getStatus().getDescription());
    return new DownstreamUnavailableException();
  }

  private static long parseLong(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("id is not a decimal long: " + value);
    }
  }

  private static Instant parseInstantMillis(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.ofEpochMilli(Long.parseLong(value.trim()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** API Key 鉴权失败；映射为 40102，不泄漏存在性。 */
  public static class InvalidApiKeyException extends RuntimeException {
    public InvalidApiKeyException() {
      super("invalid api key");
    }
  }

  /** 下游 Access 不可用；映射为 50301。 */
  public static class DownstreamUnavailableException extends RuntimeException {
    public DownstreamUnavailableException() {
      super("downstream unavailable");
    }
  }

  /** 下游 Access 超时；映射为 50401。 */
  public static class DownstreamTimeoutException extends RuntimeException {
    public DownstreamTimeoutException() {
      super("downstream timeout");
    }
  }
}
