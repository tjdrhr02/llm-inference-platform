package inference.model;

import java.time.Instant;

public class InferenceResponse {
  public enum Status {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    REJECTED
  }

  private String requestId;
  private Status status;

  private Instant receivedAt;
  private Instant startedAt;
  private Instant completedAt;
  private Long latencyMs;

  /**
   * 실제 모델 품질이 목적이 아니므로 output은 단순 문자열로 둔다.
   */
  private String output;
  private String error;

  public static InferenceResponse queued(String requestId, Instant receivedAt) {
    InferenceResponse r = new InferenceResponse();
    r.requestId = requestId;
    r.status = Status.QUEUED;
    r.receivedAt = receivedAt;
    return r;
  }

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String requestId) {
    this.requestId = requestId;
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public void setReceivedAt(Instant receivedAt) {
    this.receivedAt = receivedAt;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
  }

  public Long getLatencyMs() {
    return latencyMs;
  }

  public void setLatencyMs(Long latencyMs) {
    this.latencyMs = latencyMs;
  }

  public String getOutput() {
    return output;
  }

  public void setOutput(String output) {
    this.output = output;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }
}

