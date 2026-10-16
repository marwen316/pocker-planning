import { Injectable, inject } from "@angular/core";
import { HttpClient } from "@angular/common/http";
import { Role, Session } from "./session.model";
@Injectable({ providedIn: "root" })
export class SessionService {
  private http = inject(HttpClient);
  private url = new URL("api/session", document.baseURI).pathname;
  state() {
    return this.http.get<Session>(this.url);
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
