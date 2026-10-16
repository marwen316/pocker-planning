package fr.codenova.planning.service;

import fr.codenova.planning.api.Contracts.*;
import fr.codenova.planning.domain.Role;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlanningService {
  public static final List<String> CARDS =
      List.of("0", "1", "2", "3", "5", "8", "13", "21", "34", "COFFEE");
  private final Map<String, Member> members = new LinkedHashMap<>();
  private final Clock clock;
  private String topic = "Quelle complexité pour cette tâche ?";
  private int round = 1;
  private boolean revealed;

  public PlanningService() {
    this(Clock.systemUTC());
  }

  PlanningService(Clock clock) {
    this.clock = clock;
  }

  private static class Member {
    final String id = UUID.randomUUID().toString();
    final Join profile;
    Instant seen;
    String vote;

    Member(Join profile, Instant now) {
      this.profile = profile;
      seen = now;
    }
  }

  private void prune() {
    members.values().removeIf(m -> m.seen.plusSeconds(60).isBefore(clock.instant()));
  }

  private Member require(String id) {
    prune();
    Member m = members.get(id);
    if (m == null)
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "Rejoins la session pour continuer.");
    m.seen = clock.instant();
    return m;
  }

  public synchronized String join(String previous, Join profile) {
    prune();
    if (previous != null) members.remove(previous);
    Member m = new Member(profile, clock.instant());
    members.put(m.id, m);
    return m.id;
  }

  private boolean allVoted() {
    var devs = members.values().stream().filter(m -> m.profile.role() == Role.DEVELOPER).toList();
    return !devs.isEmpty() && devs.stream().allMatch(m -> m.vote != null);
  }

  public synchronized State state(String id) {
    Member self = require(id);
    return new State(
        topic,
        round,
        revealed,
        !revealed && (self.profile.role() == Role.VISITOR || allVoted()),
        members.values().stream()
            .map(
                m ->
                    new Participant(
                        m.id,
                        m.profile.firstName(),
                        m.profile.lastName(),
                        m.profile.role(),
                        m.vote != null,
                        revealed || m == self ? m.vote : null,
                        m == self))
            .toList(),
        CARDS);
  }

  public synchronized State vote(String id, String card) {
    Member m = require(id);
    if (m.profile.role() != Role.DEVELOPER)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Un visiteur ne peut pas voter.");
    if (revealed)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Le tour est déjà révélé.");
    if (!CARDS.contains(card))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Carte invalide.");
    m.vote = card;
    return state(id);
  }

  public synchronized State reveal(String id) {
    Member m = require(id);
    if (m.profile.role() != Role.VISITOR && !allVoted())
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Tous les développeurs doivent voter.");
    revealed = true;
    return state(id);
  }

  public synchronized State next(String id, String title) {
    require(id);
    topic = title;
    round++;
    revealed = false;
    members.values().forEach(m -> m.vote = null);
    return state(id);
  }

  public synchronized void leave(String id) {
    members.remove(id);
  }
}
