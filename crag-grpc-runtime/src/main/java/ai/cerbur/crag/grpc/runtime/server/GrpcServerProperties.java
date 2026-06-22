package ai.cerbur.crag.grpc.runtime.server;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class GrpcServerProperties {
  private int bindPort = 9090;
  private Map<String, String> allowedCallers = new LinkedHashMap<>();
  private Duration shutdownTimeout = Duration.ofSeconds(5);

  public int getBindPort() {
    return bindPort;
  }

  public void setBindPort(int bindPort) {
    this.bindPort = bindPort;
  }

  public Map<String, String> getAllowedCallers() {
    return allowedCallers;
  }

  public void setAllowedCallers(Map<String, String> allowedCallers) {
    this.allowedCallers = allowedCallers;
  }

  public Duration getShutdownTimeout() {
    return shutdownTimeout;
  }

  public void setShutdownTimeout(Duration shutdownTimeout) {
    this.shutdownTimeout = shutdownTimeout;
  }
}
