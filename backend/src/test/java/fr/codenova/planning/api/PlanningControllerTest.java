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

  private org.springframework.mock.web.MockHttpSession join(String name) throws Exception {
    return (org.springframework.mock.web.MockHttpSession)
        mvc.perform(
                post("/api/session/join")
                    .contentType("application/json")
                    .content(
                        "{\"firstName\":\""
                            + name
                            + "\",\"lastName\":\"Test\",\"role\":\"DEVELOPER\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession();
  }

  @Test
  void ssePushesPersonalizedSnapshotsOnChangesAndReconnect() throws Exception {
    var alice = join("SseAlice");
    var bob = join("SseBob");
    mvc.perform(
        post("/api/session/next")
            .session(alice)
            .contentType("application/json")
            .content("{\"title\":\"Test SSE\"}"));
    var stream =
        mvc.perform(get("/api/session/events").session(bob))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
    mvc.perform(
            put("/api/session/vote")
                .session(alice)
                .contentType("application/json")
                .content("{\"card\":\"21\"}"))
        .andExpect(status().isOk());
    String hidden = stream.getResponse().getContentAsString();
    org.assertj.core.api.Assertions.assertThat(hidden)
        .contains("event:state")
        .contains("\"voted\":true")
        .doesNotContain("\"card\":\"21\"");
    mvc.perform(
        put("/api/session/vote")
            .session(bob)
            .contentType("application/json")
            .content("{\"card\":\"5\"}"));
    mvc.perform(post("/api/session/reveal").session(alice)).andExpect(status().isOk());
    org.assertj.core.api.Assertions.assertThat(stream.getResponse().getContentAsString())
        .contains("\"card\":\"21\"");
    var reconnect =
        mvc.perform(get("/api/session/events").session(bob))
            .andExpect(request().asyncStarted())
            .andReturn();
    org.assertj.core.api.Assertions.assertThat(reconnect.getResponse().getContentAsString())
        .contains("\"revealed\":true")
        .contains("\"card\":\"21\"");
    mvc.perform(delete("/api/session").session(bob));
    org.assertj.core.api.Assertions.assertThat(stream.getResponse().getContentAsString())
        .contains("event:session-ended");
    mvc.perform(delete("/api/session").session(alice));
  }

  @Test
  void eventsRequireAValidParticipant() throws Exception {
    mvc.perform(get("/api/session/events")).andExpect(status().isUnauthorized());
  }
}
