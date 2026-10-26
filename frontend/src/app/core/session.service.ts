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
      let socket: WebSocket | undefined;
      let retry: ReturnType<typeof setTimeout> | undefined;
      let stopped = false;
      const url = new URL(this.url + "/ws", location.href);
      url.protocol = location.protocol === "https:" ? "wss:" : "ws:";
      const connect = () => {
        if (stopped) return;
        socket = new WebSocket(url);
        socket.onmessage = (event) => {
          const message = JSON.parse(event.data);
          if (message.type === "state")
            subscriber.next(message.data as Session);
        };
        socket.onclose = (event) => {
          if (stopped) return;
          if (event.code === 1008) {
            stopped = true;
            onSessionEnded();
            subscriber.complete();
          } else {
            onConnectionError();
            if (!stopped) retry = setTimeout(connect, 3000);
          }
        };
      };
      connect();
      return () => {
        stopped = true;
        clearTimeout(retry);
        socket?.close();
      };
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
