package ai.cerbur.crag.query.api;

/**
 * 无效查询异常 —— 表示用户查询因格式或长度原因无法处理.
 *
 * <p>业务层（{@link UserQueryService}）在输入校验阶段抛出，由上层 HTTP 控制器映射为 400 响应.
 */
public class InvalidQueryException extends RuntimeException {

  private final Reason reason;

  /**
   * 构造无效查询异常.
   *
   * @param reason 失败原因
   * @param message 详细描述
   */
  public InvalidQueryException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  /**
   * 返回失败原因.
   *
   * @return 枚举 {@link Reason}
   */
  public Reason getReason() {
    return reason;
  }

  /** 无效查询的原因分类. */
  public enum Reason {
    /** 查询为空或仅含空白字符. */
    QUESTION_REQUIRED,
    /** 查询超过最大长度限制. */
    QUESTION_TOO_LONG
  }
}
