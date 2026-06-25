package ai.cerbur.crag.query.context;

import ai.cerbur.crag.retrieval.api.result.ParentEvidenceResult;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 生产级 SourceBoundaryFactory —— 使用随机 nonce 并检测碰撞.
 *
 * <p>Nonce 取自 UUID（去连字符，取前 6 位小写 hex）。生成开边界前扫描全部 evidence content，确保边界字符串不会出现在原始资料中。 同一 nonce 最多重试
 * 10 次，全部碰撞则抛出 IllegalStateException.
 */
public class NonceSourceBoundaryFactory implements SourceBoundaryFactory {

  private static final int MAX_ATTEMPTS = 10;

  private final List<String> evidenceContents;
  private final Supplier<String> nonceSupplier;

  /**
   * 构造工厂.
   *
   * @param evidence evidence 列表，用于提取 content 进行碰撞扫描
   */
  public NonceSourceBoundaryFactory(List<ParentEvidenceResult> evidence) {
    this.evidenceContents = evidence.stream().map(ParentEvidenceResult::content).toList();
    this.nonceSupplier = NonceSourceBoundaryFactory::generateNonce;
  }

  /**
   * 包级可见构造器（测试用）—— 直接注入 content 与非码生成器.
   *
   * @param evidenceContents evidence content 字符串列表
   * @param nonceSupplier nonce 生成器，用于确定性测试
   */
  NonceSourceBoundaryFactory(List<String> evidenceContents, Supplier<String> nonceSupplier) {
    this.evidenceContents = List.copyOf(evidenceContents);
    this.nonceSupplier = nonceSupplier;
  }

  @Override
  public String createBoundary(String reference) {
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      String nonce = nonceSupplier.get();
      String opening = "<CRAG:" + nonce + ":" + reference + ">";
      if (!collides(opening)) {
        return opening;
      }
    }
    throw new IllegalStateException("Failed to generate nonce after " + MAX_ATTEMPTS + " attempts");
  }

  /** 生成 6 位小写 hex nonce. */
  private static String generateNonce() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase();
  }

  /**
   * 检查开边界或闭边界是否与任何 evidence content 碰撞.
   *
   * @param opening 开边界字符串，形如 {@code <CRAG:abc123:S1>}
   * @return 存在碰撞返回 true
   */
  private boolean collides(String opening) {
    String closing = "</" + opening.substring(1);
    for (String content : evidenceContents) {
      if (content.contains(opening) || content.contains(closing)) {
        return true;
      }
    }
    return false;
  }
}
