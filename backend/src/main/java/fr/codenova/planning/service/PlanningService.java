package fr.codenova.planning.service;

import fr.codenova.planning.api.Contracts.*;
import fr.codenova.planning.domain.Role;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class PlanningService {
  public static final List<String> CARDS =
      List.of("0", "1", "2", "3", "5", "8", "13", "21", "34", "COFFEE");
  private final Map<String, Member> members = new LinkedHashMap<>();
  private final Map<String, Set<SseEmitter>> streams = new HashMap<>();
  private final Clock clock;
  private String topic = "Quelle complexité pour cette tâche ?";
  private int round = 1;
  private long revision;
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
    boolean removed =
        members
            .values()
            .removeIf(
                m ->
                    !streams.containsKey(m.id) && m.seen.plusSeconds(60).isBefore(clock.instant()));
    if (removed) {
      revision++;
      broadcast();
    }
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
    if (previous != null) {
      members.remove(previous);
      closeStreams(previous);
    }
    Member m = new Member(profile, clock.instant());
    members.put(m.id, m);
    revision++;
    broadcast();
    return m.id;
  }

  private boolean allVoted() {
    var devs = members.values().stream().filter(m -> m.profile.role() == Role.DEVELOPER).toList();
    return !devs.isEmpty() && devs.stream().allMatch(m -> m.vote != null);
  }

  public synchronized State state(String id) {
    return snapshot(require(id));
  }

  private State snapshot(Member self) {
    return new State(
        revision,
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
    revision++;
    broadcast();
    return state(id);
  }

  public synchronized State reveal(String id) {
    Member m = require(id);
    if (m.profile.role() != Role.VISITOR && !allVoted())
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Tous les développeurs doivent voter.");
    revealed = true;
    revision++;
    broadcast();
    return state(id);
  }

  public synchronized State next(String id, String title) {
    require(id);
    topic = title;
    round++;
    revealed = false;
    members.values().forEach(m -> m.vote = null);
    revision++;
    broadcast();
    return state(id);
  }

  public synchronized void leave(String id) {
    if (members.remove(id) != null) revision++;
    closeStreams(id);
    broadcast();
  }

  public synchronized SseEmitter subscribe(String id) {
    Member member = require(id);
    // A bounded stream lifetime also lets the browser revalidate its HTTP session.
    SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
    streams.computeIfAbsent(id, key -> new HashSet<>()).add(emitter);
    emitter.onCompletion(() -> disconnect(id, emitter));
    emitter.onTimeout(
        () -> {
          disconnect(id, emitter);
          emitter.complete();
        });
    emitter.onError(error -> disconnect(id, emitter));
    send(id, emitter, SseEmitter.event().name("state").reconnectTime(3000).data(snapshot(member)));
    return emitter;
  }

  private synchronized void disconnect(String id, SseEmitter emitter) {
    Set<SseEmitter> connections = streams.get(id);
    if (connections != null) {
      connections.remove(emitter);
      if (connections.isEmpty()) {
        streams.remove(id);
        Member member = members.get(id);
        if (member != null) member.seen = clock.instant();
      }
    }
  }

  private void closeStreams(String id) {
    Set<SseEmitter> connections = streams.remove(id);
    if (connections != null)
      for (SseEmitter emitter : connections) {
        try {
          emitter.send(SseEmitter.event().name("session-ended").data("Session terminée"));
        } catch (IOException | IllegalStateException ignored) {
          /* Connection already closed. */
        }
        emitter.complete();
      }
  }

  private void send(String id, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
    try {
      emitter.send(event);
    } catch (IOException | IllegalStateException error) {
      disconnect(id, emitter);
      // A failed servlet write is completed by the container's async error handling.
    }
  }

  private void broadcast() {
    // Build a different snapshot per participant; never broadcast another person's hidden vote.
    for (var entry : new ArrayList<>(streams.entrySet())) {
      Member member = members.get(entry.getKey());
      if (member == null) continue;
      State state = snapshot(member);
      for (SseEmitter emitter : new ArrayList<>(entry.getValue()))
        send(member.id, emitter, SseEmitter.event().name("state").data(state));
    }
  }

  @Scheduled(fixedDelay = 15000)
  public synchronized void heartbeat() {
    for (var entry : new ArrayList<>(streams.entrySet()))
      for (SseEmitter emitter : new ArrayList<>(entry.getValue()))
        send(entry.getKey(), emitter, SseEmitter.event().comment("keepalive"));
    prune();
  }
}
