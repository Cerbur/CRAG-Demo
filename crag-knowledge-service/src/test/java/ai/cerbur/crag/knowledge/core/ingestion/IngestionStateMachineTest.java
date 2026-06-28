package ai.cerbur.crag.knowledge.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IngestionStateMachine 纯单元测试（plan_21/21.3）。
 *
 * <p>对一个 operationVersion，状态表驱动断言：
 *
 * <ul>
 *   <li>合法迁移 PENDING→PROCESSING→READY/FAILED 与 PENDING→READY/FAILED 均可应用；
 *   <li>PROCESSING→PROCESSING 自环可应用（幂等 ACK）；
 *   <li>重复终态（READY→READY、FAILED→FAILED）ACK 但不应用；
 *   <li>矛盾终态（READY→FAILED、FAILED→READY）拒绝；
 *   <li>任何到 PENDING 的事件拒绝（不可倒退）；
 *   <li>READY/FAILED 之后到 PROCESSING 拒绝（终态后不再迁移）。
 * </ul>
 *
 * <p>旧 operationVersion 场景由 {@link ai.cerbur.crag.knowledge.core.ingestion.IngestionApplyService}
 * 在比对 event.operationVersion 与 current.operationVersion 后短路 ACK，不进入本状态机。
 */
@DisplayName("IngestionStateMachine")
class IngestionStateMachineTest {

  private static final IngestionStatus PENDING = IngestionStatus.PENDING;
  private static final IngestionStatus PROCESSING = IngestionStatus.PROCESSING;
  private static final IngestionStatus READY = IngestionStatus.READY;
  private static final IngestionStatus FAILED = IngestionStatus.FAILED;

  private static IngestionTransitionDecision decide(
      IngestionStatus current, IngestionStatus event) {
    return IngestionStateMachine.decide(current, event);
  }

  @Test
  @DisplayName("PENDING→PROCESSING 可应用（APPLIED）")
  void pendingToProcessingApplied() {
    assertThat(decide(PENDING, PROCESSING).outcome()).isEqualTo(IngestionTransitionOutcome.APPLIED);
  }

  @Test
  @DisplayName("PENDING→READY 可应用（容忍中间事件丢失）")
  void pendingToReadyApplied() {
    assertThat(decide(PENDING, READY).outcome()).isEqualTo(IngestionTransitionOutcome.APPLIED);
  }

  @Test
  @DisplayName("PENDING→FAILED 可应用（容忍中间事件丢失）")
  void pendingToFailedApplied() {
    assertThat(decide(PENDING, FAILED).outcome()).isEqualTo(IngestionTransitionOutcome.APPLIED);
  }

  @Test
  @DisplayName("PROCESSING→READY 可应用")
  void processingToReadyApplied() {
    assertThat(decide(PROCESSING, READY).outcome()).isEqualTo(IngestionTransitionOutcome.APPLIED);
  }

  @Test
  @DisplayName("PROCESSING→FAILED 可应用")
  void processingToFailedApplied() {
    assertThat(decide(PROCESSING, FAILED).outcome()).isEqualTo(IngestionTransitionOutcome.APPLIED);
  }

  @Test
  @DisplayName("PROCESSING→PROCESSING 可应用（幂等重投递）")
  void processingToProcessingApplied() {
    assertThat(decide(PROCESSING, PROCESSING).outcome())
        .isEqualTo(IngestionTransitionOutcome.APPLIED);
  }

  @Test
  @DisplayName("PENDING→PENDING 可应用（幂等重投递，无字段变化）")
  void pendingToPendingApplied() {
    assertThat(decide(PENDING, PENDING).outcome()).isEqualTo(IngestionTransitionOutcome.APPLIED);
  }

  @Test
  @DisplayName("重复 READY 终态 → ACK 但不应用")
  void duplicateReadyAckedNotApplied() {
    IngestionTransitionDecision decision = decide(READY, READY);
    assertThat(decision.outcome()).isEqualTo(IngestionTransitionOutcome.ACKNOWLEDGED);
  }

  @Test
  @DisplayName("重复 FAILED 终态 → ACK 但不应用")
  void duplicateFailedAckedNotApplied() {
    IngestionTransitionDecision decision = decide(FAILED, FAILED);
    assertThat(decision.outcome()).isEqualTo(IngestionTransitionOutcome.ACKNOWLEDGED);
  }

  @Test
  @DisplayName("矛盾终态 READY→FAILED → 拒绝（不覆盖事实）")
  void contradictoryReadyToFailedRejected() {
    assertThat(decide(READY, FAILED).outcome()).isEqualTo(IngestionTransitionOutcome.REJECTED);
  }

  @Test
  @DisplayName("矛盾终态 FAILED→READY → 拒绝（不覆盖事实）")
  void contradictoryFailedToReadyRejected() {
    assertThat(decide(FAILED, READY).outcome()).isEqualTo(IngestionTransitionOutcome.REJECTED);
  }

  @Test
  @DisplayName("终态后到 PROCESSING → 拒绝（终态后不再迁移）")
  void terminalToProcessingRejected() {
    assertThat(decide(READY, PROCESSING).outcome()).isEqualTo(IngestionTransitionOutcome.REJECTED);
    assertThat(decide(FAILED, PROCESSING).outcome()).isEqualTo(IngestionTransitionOutcome.REJECTED);
  }

  @Test
  @DisplayName("任何到 PENDING 的事件 → 拒绝（不可倒退）")
  void anyToPendingRejected() {
    assertThat(decide(PROCESSING, PENDING).outcome())
        .isEqualTo(IngestionTransitionOutcome.REJECTED);
    assertThat(decide(READY, PENDING).outcome()).isEqualTo(IngestionTransitionOutcome.REJECTED);
    assertThat(decide(FAILED, PENDING).outcome()).isEqualTo(IngestionTransitionOutcome.REJECTED);
  }
}
