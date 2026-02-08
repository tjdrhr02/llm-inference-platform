package inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import inference.model.InferenceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "inference.concurrency.maxConcurrent=1",
    "inference.concurrency.workerThreads=1",
    "inference.concurrency.queueCapacity=0",
    "inference.concurrency.acquireTimeoutMs=50",
    "inference.processing.timeoutMs=5000",
    "inference.processing.simulatedMinMs=1000",
    "inference.processing.simulatedMaxMs=1000"
})
@AutoConfigureMockMvc
class InferenceApiQueueFullTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper om;

  @Test
  void whenQueueIsFullPostReturns429AndRejected() throws Exception {
    // 1) 첫 요청으로 워커 스레드를 점유시켜 queueCapacity=0에서 다음 요청을 거절 유도
    mvc.perform(post("/v1/inference")
            .header("X-Request-Id", "qfull-1")
            .contentType(APPLICATION_JSON)
            .content("""
                {"prompt":"hold thread","model":"dummy"}
                """))
        .andExpect(status().isAccepted());

    Thread.sleep(80); // 워커가 RUNNING에 들어갈 시간을 조금 준다

    // 2) 두 번째 요청은 큐가 없어서 즉시 거절(429)
    var res = mvc.perform(post("/v1/inference")
            .header("X-Request-Id", "qfull-2")
            .contentType(APPLICATION_JSON)
            .content("""
                {"prompt":"should be rejected","model":"dummy"}
                """))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("X-Request-Id", "qfull-2"))
        .andReturn();

    InferenceResponse body = om.readValue(res.getResponse().getContentAsByteArray(), InferenceResponse.class);
    assertThat(body.getStatus()).isEqualTo(InferenceResponse.Status.REJECTED);
    assertThat(body.getError()).isEqualTo("queue_full");
  }
}

