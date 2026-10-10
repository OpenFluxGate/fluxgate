package org.fluxgate.spring.autoconfigure;

import com.mongodb.client.MongoClient;
import java.util.Objects;

/**
 * Holds the {@link MongoClient} FluxGate uses for its own rule and event collections.
 *
 * <p>The client is deliberately not registered as a {@code MongoClient} bean: an application that
 * uses Spring Data MongoDB, or injects {@code MongoClient} anywhere, must keep getting its own
 * client. Exposing FluxGate's client under that type made Boot's {@code MongoAutoConfiguration}
 * back off (application data was then written to the FluxGate cluster) or made the {@code
 * MongoClient} injection ambiguous.
 *
 * <p>Inject this type to reach FluxGate's client. The holder closes the client on shutdown only
 * when it created it.
 *
 * @since 0.4.0
 */
public final class FluxgateMongoClientHolder implements AutoCloseable {

  private final MongoClient client;
  private final boolean owned;

  /**
   * Creates a holder.
   *
   * @param client the client FluxGate uses (must not be null)
   * @param owned whether the holder created the client and must close it
   */
  public FluxgateMongoClientHolder(MongoClient client, boolean owned) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.owned = owned;
  }

  /**
   * Returns FluxGate's MongoDB client.
   *
   * @return the client
   */
  public MongoClient getClient() {
    return client;
  }

  /**
   * Whether the client was created by FluxGate and is closed with this holder.
   *
   * @return true when the holder owns the client
   */
  public boolean isOwned() {
    return owned;
  }

  /** Closes the client when the holder created it. */
  @Override
  public void close() {
    if (owned) {
      client.close();
    }
  }
}
