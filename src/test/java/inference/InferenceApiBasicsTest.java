package inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

@SpringBootTest
@AutoConfigureMockMvc
class InferenceApiBasicsTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper om;

  @Test
  void postGeneratesRequestIdAndReturns202() throws Exception {
    var res = mvc.perform(post("/v1/inference")
            .contentType(APPLICATION_JSON)
            .content("""
                {"prompt":"hello","model":"dummy"}
                """))
        .andExpect(status().isAccepted())
        .andExpect(header().exists("X-Request-Id"))
        .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/v1/inference/")))
        .andReturn();

    InferenceResponse body = om.readValue(res.getResponse().getContentAsByteArray(), InferenceResponse.class);
    assertThat(body.getRequestId()).isNotBlank();
    // 비동기 실행이 매우 빠르면 응답 시점에 RUNNING으로 바뀔 수 있음
    assertThat(body.getStatus()).isIn(InferenceResponse.Status.QUEUED, InferenceResponse.Status.RUNNING);
  }

  @Test
  void getUnknownRequestReturns404() throws Exception {
    mvc.perform(get("/v1/inference/does-not-exist"))
        .andExpect(status().isNotFound());
  }
}

