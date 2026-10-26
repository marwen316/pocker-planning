package fr.codenova.planning.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import fr.codenova.planning.PlanningApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@org.springframework.test.context.TestExecutionListeners(
    listeners =
        org.springframework.test.context.support.DependencyInjectionTestExecutionListener.class,
    mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
@SpringBootTest(classes = PlanningApplication.class)
@AutoConfigureMockMvc
class PlanningControllerTest {
  @Autowired MockMvc mvc;

  @Test
  void validationRejectsEmptyNames() throws Exception {
    mvc.perform(
            post("/api/session/join")
                .contentType("application/json")
                .content("{\"firstName\":\"\",\"lastName\":\"Test\",\"role\":\"DEVELOPER\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unauthenticatedAccessIsRejected() throws Exception {
    mvc.perform(get("/api/session")).andExpect(status().isUnauthorized());
  }

  @Test
  void browserSessionCanJoinAndVote() throws Exception {
    var result =
        mvc.perform(
                post("/api/session/join")
                    .contentType("application/json")
                    .content(
                        "{\"firstName\":\"Alice\",\"lastName\":\"Test\",\"role\":\"DEVELOPER\"}"))
            .andExpect(status().isOk())
            .andReturn();
    var session = (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession();
    mvc.perform(
        post("/api/session/next")
            .session(session)
            .contentType("application/json")
            .content("{\"title\":\"Test HTTP\"}"));
    mvc.perform(
            put("/api/session/vote")
                .session(session)
                .contentType("application/json")
                .content("{\"card\":\"8\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.participants[0].card").value("8"));
    mvc.perform(delete("/api/session").session(session)).andExpect(status().isOk());
  }
}
