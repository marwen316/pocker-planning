import { Component, DestroyRef, inject, signal, computed } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { Observable, Subscription } from "rxjs";
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
  private events?: Subscription;
  private checkingConnection = false;
  constructor() {
    this.api
      .state()
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (s) => {
          this.apply(s);
          this.connect();
        },
        error: () => {},
      });
    this.destroy.onDestroy(() => this.events?.unsubscribe());
  }
  private connect() {
    if (this.events && !this.events.closed) return;
    this.events = this.api
      .events(
        () => {
          this.error.set(
            "Connexion interrompue. Reconnexion automatique en cours…",
          );
          // One diagnostic request per failed connection, never a periodic poll.
          if (this.checkingConnection) return;
          this.checkingConnection = true;
          this.api
            .state()
            .pipe(takeUntilDestroyed(this.destroy))
            .subscribe({
              next: () => {
                this.checkingConnection = false;
              },
              error: (e) => {
                this.checkingConnection = false;
                if (e.status === 401) this.endSession();
              },
            });
        },
        () => this.endSession(),
      )
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe((s) => {
        this.apply(s);
        if (this.error().startsWith("Connexion")) this.error.set("");
      });
  }
  private apply(state: Session) {
    // A delayed action response must not overwrite a newer WebSocket snapshot.
    if (!this.session() || state.revision >= this.session()!.revision)
      this.session.set(state);
  }
  private endSession() {
    this.events?.unsubscribe();
    this.events = undefined;
    this.session.set(null);
  }
  run(request: Observable<Session>) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set("");
    request.subscribe({
      next: (s) => {
        this.apply(s);
        this.connect();
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
    this.api.leave().subscribe({
      next: () => this.endSession(),
      error: () => this.error.set("Impossible de quitter. Réessaie."),
    });
  }
  label(card: string | null) {
    return card === "COFFEE" ? "☕" : (card ?? "—");
  }
}
