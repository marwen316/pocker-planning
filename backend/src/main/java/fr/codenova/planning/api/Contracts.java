package fr.codenova.planning.api;

import fr.codenova.planning.domain.Role;
import jakarta.validation.constraints.*;
import java.util.List;

public final class Contracts {
  private Contracts() {}

  public record Join(
      @NotBlank @Size(max = 40) String firstName,
      @NotBlank @Size(max = 40) String lastName,
      @NotNull Role role) {}

  public record Vote(@NotBlank String card) {}

  public record Topic(@NotBlank @Size(max = 160) String title) {}

  public record Participant(
      String id,
      String firstName,
      String lastName,
      Role role,
      boolean voted,
      String card,
      boolean self) {}

  public record State(
      String topic,
      int round,
      boolean revealed,
      boolean canReveal,
      List<Participant> participants,
      List<String> cards) {}
}
