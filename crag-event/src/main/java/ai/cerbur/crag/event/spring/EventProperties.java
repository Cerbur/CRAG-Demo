package ai.cerbur.crag.event.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@code crag.event.*}, bound by {@link EventAutoConfiguration}.
 *
 * <p>Publisher and consumer are disabled by default; a service opts in by setting {@code
 * crag.event.publisher.enabled=true} and/or {@code crag.event.consumer.enabled=true}. Defaults keep
 * a smoke-shaped closed loop working without further tuning.
 */
@ConfigurationProperties(prefix = "crag.event")
public class EventProperties {

  private Publisher publisher = new Publisher();
  private Consumer consumer = new Consumer();
  private Health health = new Health();
  private Backoff backoff = new Backoff();
  private String streamKey = "crag:event:default";
  private String dlqStreamKey = "crag:event:default:dlq";
  private String groupName = "crag-event";
  private String consumerName = "crag-event-consumer";
  private int batchSize = 20;
  private Duration claimIdle = Duration.ofSeconds(30);
  private long maxDeliveries = 3;
  private Duration pollInterval = Duration.ofSeconds(1);

  public Publisher getPublisher() {
    return publisher;
  }

  public void setPublisher(Publisher publisher) {
    this.publisher = publisher;
  }

  public Consumer getConsumer() {
    return consumer;
  }

  public void setConsumer(Consumer consumer) {
    this.consumer = consumer;
  }

  public Health getHealth() {
    return health;
  }

  public void setHealth(Health health) {
    this.health = health;
  }

  public Backoff getBackoff() {
    return backoff;
  }

  public void setBackoff(Backoff backoff) {
    this.backoff = backoff;
  }

  public String getStreamKey() {
    return streamKey;
  }

  public void setStreamKey(String streamKey) {
    this.streamKey = streamKey;
  }

  public String getDlqStreamKey() {
    return dlqStreamKey;
  }

  public void setDlqStreamKey(String dlqStreamKey) {
    this.dlqStreamKey = dlqStreamKey;
  }

  public String getGroupName() {
    return groupName;
  }

  public void setGroupName(String groupName) {
    this.groupName = groupName;
  }

  public String getConsumerName() {
    return consumerName;
  }

  public void setConsumerName(String consumerName) {
    this.consumerName = consumerName;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public Duration getClaimIdle() {
    return claimIdle;
  }

  public void setClaimIdle(Duration claimIdle) {
    this.claimIdle = claimIdle;
  }

  public long getMaxDeliveries() {
    return maxDeliveries;
  }

  public void setMaxDeliveries(long maxDeliveries) {
    this.maxDeliveries = maxDeliveries;
  }

  public Duration getPollInterval() {
    return pollInterval;
  }

  public void setPollInterval(Duration pollInterval) {
    this.pollInterval = pollInterval;
  }

  /** Publisher toggle and tuning. */
  public static class Publisher {
    private boolean enabled = false;
    private int maxAttempts = 5;
    private Duration claimDuration = Duration.ofSeconds(30);

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getMaxAttempts() {
      return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
    }

    public Duration getClaimDuration() {
      return claimDuration;
    }

    public void setClaimDuration(Duration claimDuration) {
      this.claimDuration = claimDuration;
    }
  }

  /** Consumer toggle. */
  public static class Consumer {
    private boolean enabled = false;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }

  /** Health indicator toggle. */
  public static class Health {
    private boolean enabled = false;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }

  /** Backoff bounds for outbox retries. */
  public static class Backoff {
    private Duration initial = Duration.ofSeconds(1);
    private Duration max = Duration.ofSeconds(30);

    public Duration getInitial() {
      return initial;
    }

    public void setInitial(Duration initial) {
      this.initial = initial;
    }

    public Duration getMax() {
      return max;
    }

    public void setMax(Duration max) {
      this.max = max;
    }
  }
}
