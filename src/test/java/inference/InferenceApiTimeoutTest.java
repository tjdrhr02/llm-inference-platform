package inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import inference.model.InferenceResponse;
import inference.testsupport.Polling;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "inference.concurrency.maxConcurrent=1",
    "inference.concurrency.workerThreads=1",
    "inference.concurrency.queueCapacity=10",
    "inference.concurrency.acquireTimeoutMs=10",
    "inference.processing.timeoutMs=50",
    "inference.processing.simulatedMinMs=200",
    "inference.processing.simulatedMaxMs=200"
})
@AutoConfigureMockMvc
class InferenceApiTimeoutTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper om;

  @Test
  void inferenceTimesOutAndIsReportedByGet() throws Exception {
    String rid = "timeout-1";
    mvc.perform(post("/v1/inference")
            .header("X-Request-Id", rid)
            .contentType(APPLICATION_JSON)
            .content("""
                {"prompt":"this will timeout","model":"dummy"}
                """))
        .andExpect(status().isAccepted())
        .andExpect(header().string("X-Request-Id", rid));

    Polling.waitUntil(Duration.ofSeconds(3), Duration.ofMillis(50), () -> {
      var res = mvc.perform(get("/v1/inference/" + rid))
          .andExpect(status().isOk())
          .andReturn();
      InferenceResponse body = om.readValue(res.getResponse().getContentAsByteArray(), InferenceResponse.class);
      return body.getStatus() == InferenceResponse.Status.FAILED && "timeout".equals(body.getError());
    });

    var finalRes = mvc.perform(get("/v1/inference/" + rid))
        .andExpect(status().isOk())
        .andReturn();
    InferenceResponse body = om.readValue(finalRes.getResponse().getContentAsByteArray(), InferenceResponse.class);
    assertThat(body.getLatencyMs()).isNotNull();
    assertThat(body.getLatencyMs()).isGreaterThan(0L);
  }
}

