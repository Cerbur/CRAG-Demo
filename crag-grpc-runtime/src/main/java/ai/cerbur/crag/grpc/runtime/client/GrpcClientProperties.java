package ai.cerbur.crag.grpc.runtime.client;

public class GrpcClientProperties {
  private String callerService = "";
  private String token = "";
  private long maxDeadlineMillis = 10000;
  private long channelShutdownTimeoutMillis = 5000;

  public String getCallerService() {
    return callerService;
  }

  public void setCallerService(String callerService) {
    this.callerService = callerService;
  }

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public long getMaxDeadlineMillis() {
    return maxDeadlineMillis;
  }

  public void setMaxDeadlineMillis(long maxDeadlineMillis) {
    this.maxDeadlineMillis = maxDeadlineMillis;
  }

  public long getChannelShutdownTimeoutMillis() {
    return channelShutdownTimeoutMillis;
  }

  public void setChannelShutdownTimeoutMillis(long channelShutdownTimeoutMillis) {
    this.channelShutdownTimeoutMillis = channelShutdownTimeoutMillis;
  }

  @Override
  public String toString() {
    return "GrpcClientProperties{callerService='" + callerService + "', token='[REDACTED]'}";
  }
}
