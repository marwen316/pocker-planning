package fr.codenova.planning.service;

import static org.assertj.core.api.Assertions.*;

import fr.codenova.planning.api.Contracts.Join;
import fr.codenova.planning.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PlanningServiceTest {
  PlanningService service = new PlanningService();

  String dev(String name) {
    return service.join(null, new Join(name, "Test", Role.DEVELOPER));
  }

  String visitor() {
    return service.join(null, new Join("Visitor", "Test", Role.VISITOR));
  }

  @Test
  void votesRemainSecretUntilEveryoneVotesAndRevealIsExplicit() {
    String a = dev("Alice"), b = dev("Bob");
    service.vote(a, "8");
    assertThat(service.state(b).participants().getFirst().card()).isNull();
    assertThatThrownBy(() -> service.reveal(a)).isInstanceOf(ResponseStatusException.class);
    service.vote(b, "13");
    assertThat(service.state(a).canReveal()).isTrue();
    assertThat(service.state(b).participants().getFirst().card()).isNull();
    assertThat(service.reveal(a).participants().getFirst().card()).isEqualTo("8");
  }

  @Test
  void visitorCanRevealEarlyButCannotVote() {
    String a = dev("Alice"), v = visitor();
    service.vote(a, "5");
    assertThatThrownBy(() -> service.vote(v, "3")).isInstanceOf(ResponseStatusException.class);
    assertThat(service.reveal(v).revealed()).isTrue();
  }

  @Test
  void coffeeCountsAsVoteAndNextRoundResetsAllCards() {
    String a = dev("Alice");
    service.vote(a, "COFFEE");
    assertThat(service.reveal(a).revealed()).isTrue();
    var next = service.next(a, "Nouvelle tâche");
    assertThat(next.revealed()).isFalse();
    assertThat(next.round()).isEqualTo(2);
    assertThat(next.participants().getFirst().voted()).isFalse();
    assertThat(next.participants().getFirst().card()).isNull();
  }

  @Test
  void illegalCardAndVoteAfterRevealAreRejected() {
    String a = dev("Alice");
    assertThatThrownBy(() -> service.vote(a, "55")).isInstanceOf(ResponseStatusException.class);
    service.vote(a, "34");
    service.reveal(a);
    assertThatThrownBy(() -> service.vote(a, "3")).isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void leavingRemovesBlocker() {
    String a = dev("Alice"), b = dev("Bob");
    service.vote(a, "3");
    service.leave(b);
    assertThat(service.state(a).canReveal()).isTrue();
  }

  @Test
  void unknownParticipantCannotAct() {
    assertThatThrownBy(() -> service.state("unknown")).isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> service.next(null, "Task"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void silentParticipantsExpire() {
    class MutableClock extends java.time.Clock {
      java.time.Instant now = java.time.Instant.parse("2026-01-01T00:00:00Z");

      public java.time.ZoneId getZone() {
        return java.time.ZoneOffset.UTC;
      }

      public java.time.Clock withZone(java.time.ZoneId z) {
        return this;
      }

      public java.time.Instant instant() {
        return now;
      }
    }
    var clock = new MutableClock();
    var s = new PlanningService(clock);
    var a = s.join(null, new Join("Alice", "Test", Role.DEVELOPER));
    clock.now = clock.now.plusSeconds(61);
    assertThatThrownBy(() -> s.state(a)).isInstanceOf(ResponseStatusException.class);
  }
}
