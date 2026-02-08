package inference;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class InferenceApiValidationTest {
  @Autowired MockMvc mvc;

  @Test
  void missingPromptReturns400ProblemDetail() throws Exception {
    mvc.perform(post("/v1/inference")
            .contentType(APPLICATION_JSON)
            .content("""
                {"model":"dummy"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("validation_failed"))
        .andExpect(jsonPath("$.fields.prompt").exists());
  }
}

