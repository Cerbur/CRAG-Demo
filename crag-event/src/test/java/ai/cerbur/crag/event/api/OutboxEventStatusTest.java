package ai.cerbur.crag.event.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("OutboxEventStatus")
class OutboxEventStatusTest {

  @Nested
  @DisplayName("legal transitions")
  class LegalTransitions {

    @Test
    @DisplayName("PENDING may transition to PUBLISHING")
    void pendingToPublishing() {
      assertThat(OutboxEventStatus.PENDING.canTransitionTo(OutboxEventStatus.PUBLISHING)).isTrue();
    }

    @Test
    @DisplayName("PUBLISHING may transition to PUBLISHED, RETRY_WAIT and DEAD")
    void publishingTargets() {
      assertThat(OutboxEventStatus.PUBLISHING.canTransitionTo(OutboxEventStatus.PUBLISHED))
          .isTrue();
      assertThat(OutboxEventStatus.PUBLISHING.canTransitionTo(OutboxEventStatus.RETRY_WAIT))
          .isTrue();
      assertThat(OutboxEventStatus.PUBLISHING.canTransitionTo(OutboxEventStatus.DEAD)).isTrue();
    }

    @Test
    @DisplayName("RETRY_WAIT may reclaim to PUBLISHING or move to DEAD")
    void retryWaitTargets() {
      assertThat(OutboxEventStatus.RETRY_WAIT.canTransitionTo(OutboxEventStatus.PUBLISHING))
          .isTrue();
      assertThat(OutboxEventStatus.RETRY_WAIT.canTransitionTo(OutboxEventStatus.DEAD)).isTrue();
    }
  }

  @Nested
  @DisplayName("illegal transitions")
  class IllegalTransitions {

    @Test
    @DisplayName("PENDING may not jump to PUBLISHED or DEAD")
    void pendingSkipsPublishing() {
      assertThat(OutboxEventStatus.PENDING.canTransitionTo(OutboxEventStatus.PUBLISHED)).isFalse();
      assertThat(OutboxEventStatus.PENDING.canTransitionTo(OutboxEventStatus.DEAD)).isFalse();
    }

    @Test
    @DisplayName("PUBLISHED is terminal")
    void publishedIsTerminal() {
      assertThat(OutboxEventStatus.PUBLISHED.isTerminal()).isTrue();
      assertThat(OutboxEventStatus.PUBLISHED.canTransitionTo(OutboxEventStatus.PUBLISHING))
          .isFalse();
    }

    @Test
    @DisplayName("DEAD is terminal")
    void deadIsTerminal() {
      assertThat(OutboxEventStatus.DEAD.isTerminal()).isTrue();
      assertThat(OutboxEventStatus.DEAD.canTransitionTo(OutboxEventStatus.PUBLISHING)).isFalse();
    }

    @Test
    @DisplayName("requireTransitionTo throws on illegal move")
    void requireThrowsOnIllegal() {
      assertThatThrownBy(
              () -> OutboxEventStatus.PUBLISHED.requireTransitionTo(OutboxEventStatus.PUBLISHING))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("requireTransitionTo returns target on legal move")
    void requireReturnsTargetOnLegal() {
      assertThat(OutboxEventStatus.PENDING.requireTransitionTo(OutboxEventStatus.PUBLISHING))
          .isEqualTo(OutboxEventStatus.PUBLISHING);
    }
  }
}
