package inference.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public class InferenceRequest {
  /**
   * 클라이언트가 이미 추적용 ID를 갖고 있다면 넣어도 됨(선택).
   * 없으면 서버가 requestId를 생성한다.
   */
  private String clientRequestId;

  @NotBlank
  @Size(max = 20000)
  private String prompt;

  /**
   * 모델명은 정확도가 목적이 아니므로 단순 식별자(선택).
   */
  private String model;

  /**
   * 온도/탑P 등의 파라미터를 유연하게 받기 위한 필드(선택).
   */
  private Map<String, Object> parameters;

  public String getClientRequestId() {
    return clientRequestId;
  }

  public void setClientRequestId(String clientRequestId) {
    this.clientRequestId = clientRequestId;
  }

  public String getPrompt() {
    return prompt;
  }

  public void setPrompt(String prompt) {
    this.prompt = prompt;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public Map<String, Object> getParameters() {
    return parameters;
  }

  public void setParameters(Map<String, Object> parameters) {
    this.parameters = parameters;
  }
}

