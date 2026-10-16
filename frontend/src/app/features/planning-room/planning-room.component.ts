import { Component, DestroyRef, inject, signal, computed } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { Observable, timer, exhaustMap, catchError, EMPTY } from "rxjs";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { SessionService } from "../../core/session.service";
import { Session, Role } from "../../core/session.model";
@Component({
  selector: "app-planning-room",
  standalone: true,
  imports: [FormsModule],
  templateUrl: "./planning-room.component.html",
})
export class PlanningRoomComponent {
  private api = inject(SessionService);
  private destroy = inject(DestroyRef);
  session = signal<Session | null>(null);
  error = signal("");
  busy = signal(false);
  firstName = "";
  lastName = "";
  role: Role = "DEVELOPER";
  title = "";
  me = computed(() => this.session()?.participants.find((p) => p.self));
  developers = computed(
    () =>
      this.session()?.participants.filter((p) => p.role === "DEVELOPER") ?? [],
  );
  voted = computed(() => this.developers().filter((p) => p.voted).length);
  average = computed(() => {
    const votes = this.developers()
      .map((p) => p.card)
      .filter((c): c is string => c !== null && c !== "COFFEE")
      .map(Number);
    return votes.length
      ? (votes.reduce((a, b) => a + b, 0) / votes.length).toLocaleString(
          "fr-FR",
          { maximumFractionDigits: 1 },
        )
      : "—";
  });
  constructor() {
    this.api
      .state()
      .subscribe({ next: (s) => this.session.set(s), error: () => {} });
    timer(1000, 1000)
      .pipe(
        exhaustMap(() =>
          this.session()
            ? this.api.state().pipe(
                catchError((e) => {
                  if (e.status === 401) this.session.set(null);
                  else
                    this.error.set(
                      "Connexion interrompue. Nouvelle tentative en cours…",
                    );
                  return EMPTY;
                }),
              )
            : EMPTY,
        ),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe((s) => {
        this.session.set(s);
        if (this.error().startsWith("Connexion")) this.error.set("");
      });
  }
  run(request: Observable<Session>) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set("");
    request.subscribe({
      next: (s) => {
        this.session.set(s);
        this.busy.set(false);
      },
      error: (e) => {
        this.error.set(
          e.error?.message ?? "Action impossible. Réessaie dans un instant.",
        );
        this.busy.set(false);
      },
    });
  }
  join() {
    if (this.firstName.trim() && this.lastName.trim())
      this.run(
        this.api.join(this.firstName.trim(), this.lastName.trim(), this.role),
      );
  }
  vote(card: string) {
    this.run(this.api.vote(card));
  }
  reveal() {
    this.run(this.api.reveal());
  }
  next() {
    this.run(this.api.next(this.title.trim() || this.session()!.topic));
    this.title = "";
  }
  leave() {
    this.api
      .leave()
      .subscribe({
        next: () => this.session.set(null),
        error: () => this.error.set("Impossible de quitter. Réessaie."),
      });
  }
  label(card: string | null) {
    return card === "COFFEE" ? "☕" : (card ?? "—");
  }
}
