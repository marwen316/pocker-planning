import { Injectable, inject } from "@angular/core";
import { HttpClient } from "@angular/common/http";
import { Observable } from "rxjs";
import { Role, Session } from "./session.model";
@Injectable({ providedIn: "root" })
export class SessionService {
  private http = inject(HttpClient);
  private url = new URL("api/session", document.baseURI).pathname;
  state() {
    return this.http.get<Session>(this.url);
  }
  events(onConnectionError: () => void, onSessionEnded: () => void) {
    return new Observable<Session>((subscriber) => {
      const source = new EventSource(this.url + "/events");
      source.addEventListener("state", (event) => {
        subscriber.next(JSON.parse((event as MessageEvent).data) as Session);
      });
      source.addEventListener("session-ended", () => {
        source.close();
        onSessionEnded();
        subscriber.complete();
      });
      source.onerror = () => onConnectionError();
      return () => source.close();
    });
  }
  join(firstName: string, lastName: string, role: Role) {
    return this.http.post<Session>(this.url + "/join", {
      firstName,
      lastName,
      role,
    });
  }
  vote(card: string) {
    return this.http.put<Session>(this.url + "/vote", { card });
  }
  reveal() {
    return this.http.post<Session>(this.url + "/reveal", {});
  }
  next(title: string) {
    return this.http.post<Session>(this.url + "/next", { title });
  }
  leave() {
    return this.http.delete<void>(this.url);
  }
}
