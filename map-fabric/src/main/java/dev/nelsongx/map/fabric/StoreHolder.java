package dev.nelsongx.map.fabric;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds one asynchronously opened store (SQLite {@code FeatureStore} or {@code SessionStore}) for the
 * lifetime of a Minecraft server.
 *
 * @param <T> store type
 */
// THREADING: get() — any thread, non-blocking (volatile). open() and close() do blocking I/O and run
// on the MapExecutor only. The swap of `store` and the `closed` flag are guarded by `this` with the
// I/O outside the lock, so a close that runs before a late open wins and the late store is closed
// immediately.
public final class StoreHolder<T extends AutoCloseable> {

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");

  private final String label;
  private volatile T store;
  private boolean closed;

  /** @param label name used in log messages */
  public StoreHolder(String label) {
    this.label = label;
  }

  /** @return the open store, or null while not (yet) open or after close. ANY THREAD. */
  public T get() {
    return store;
  }

  /**
   * Opens the store. WORKER THREAD ONLY (blocking I/O). Failures are logged.
   *
   * @param opener opens the store
   */
  public void open(Supplier<T> opener) {
    synchronized (this) {
      if (closed) {
        return;
      }
    }
    T opened;
    try {
      opened = opener.get();
    } catch (RuntimeException e) {
      LOGGER.error("squaremap-pro could not open the {}", label, e);
      return;
    }
    T previous;
    synchronized (this) {
      if (closed) {
        previous = opened;
      } else {
        previous = store;
        store = opened;
      }
    }
    closeQuietly(previous);
  }

  /** Closes the store; later opens are ignored. WORKER THREAD ONLY (blocking I/O). */
  public void close() {
    T s;
    synchronized (this) {
      closed = true;
      s = store;
      store = null;
    }
    closeQuietly(s);
  }

  private void closeQuietly(T s) {
    if (s == null) {
      return;
    }
    try {
      s.close();
    } catch (Exception e) {
      LOGGER.warn("squaremap-pro {} did not close cleanly", label, e);
    }
  }
}
