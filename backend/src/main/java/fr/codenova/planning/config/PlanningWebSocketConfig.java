package fr.codenova.planning.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.codenova.planning.api.Contracts.State;
import fr.codenova.planning.service.LiveConnection;
import fr.codenova.planning.service.PlanningService;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

@Configuration
@EnableWebSocket
public class PlanningWebSocketConfig implements WebSocketConfigurer {
  private final PlanningService service;
  private final ObjectMapper mapper;

  public PlanningWebSocketConfig(PlanningService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(
            new TextWebSocketHandler() {
              @Override
              public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                String id = (String) session.getAttributes().get("participant");
                LiveConnection connection =
                    new LiveConnection() {
                      private void send(String type, Object data) throws IOException {
                        session.sendMessage(
                            new TextMessage(
                                mapper.writeValueAsString(Map.of("type", type, "data", data))));
                      }

                      public void state(State state) throws IOException {
                        send("state", state);
                      }

                      public void heartbeat() throws IOException {
                        HttpSession http = (HttpSession) session.getAttributes().get("httpSession");
                        if (http == null || !id.equals(http.getAttribute("participant"))) {
                          close();
                          return;
                        }
                        send("heartbeat", "keepalive");
                      }

                      public void close() throws IOException {
                        session.close(CloseStatus.POLICY_VIOLATION);
                      }
                    };
                session.getAttributes().put("connection", connection);
                try {
                  service.subscribe(id, connection);
                } catch (ResponseStatusException error) {
                  session.close(CloseStatus.POLICY_VIOLATION);
                }
              }

              @Override
              public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                service.disconnect(
                    (String) session.getAttributes().get("participant"),
                    (LiveConnection) session.getAttributes().get("connection"));
              }

              @Override
              public void handleTransportError(WebSocketSession session, Throwable error)
                  throws Exception {
                afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
                if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
              }
            },
            "/api/session/ws")
        .addInterceptors(
            new HttpSessionHandshakeInterceptor(List.of("participant")) {
              @Override
              public boolean beforeHandshake(
                  ServerHttpRequest request,
                  ServerHttpResponse response,
                  WebSocketHandler handler,
                  Map<String, Object> attributes)
                  throws Exception {
                if (!super.beforeHandshake(request, response, handler, attributes)) return false;
                try {
                  service.state((String) attributes.get("participant"));
                  attributes.put(
                      "httpSession",
                      ((ServletServerHttpRequest) request).getServletRequest().getSession(false));
                  return true;
                } catch (ResponseStatusException error) {
                  response.setStatusCode(HttpStatus.UNAUTHORIZED);
                  return false;
                }
              }
            });
    // Spring's default same-origin policy is retained; no wildcard origin authorization.
  }
}
