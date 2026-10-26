package fr.codenova.planning.api;

import fr.codenova.planning.api.Contracts.*;
import fr.codenova.planning.service.PlanningService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/session")
public class PlanningController {
  private final PlanningService service;

  public PlanningController(PlanningService service) {
    this.service = service;
  }

  private String id(HttpSession s) {
    return (String) s.getAttribute("participant");
  }

  @PostMapping("/join")
  public State join(@Valid @RequestBody Join body, HttpSession s) {
    s.setAttribute("participant", service.join(id(s), body));
    return service.state(id(s));
  }

  @GetMapping
  public State state(HttpSession s) {
    return service.state(id(s));
  }

  @PutMapping("/vote")
  public State vote(@Valid @RequestBody Vote body, HttpSession s) {
    return service.vote(id(s), body.card());
  }

  @PostMapping("/reveal")
  public State reveal(HttpSession s) {
    return service.reveal(id(s));
  }

  @PostMapping("/next")
  public State next(@Valid @RequestBody Topic body, HttpSession s) {
    return service.next(id(s), body.title());
  }

  @DeleteMapping
  public void leave(HttpSession s) {
    service.leave(id(s));
    s.invalidate();
  }
}
