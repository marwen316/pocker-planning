package fr.codenova.planning.service;

import fr.codenova.planning.api.Contracts.State;
import java.io.IOException;

/** One authenticated participant connection; transport remains outside the business rules. */
public interface LiveConnection {
  void state(State state) throws IOException;

  void heartbeat() throws IOException;

  void close() throws IOException;
}
