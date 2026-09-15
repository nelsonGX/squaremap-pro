package dev.nelsongx.map.core.store;

/** An unexpected storage failure (I/O, SQL error, corrupt or unsupported database). */
public class FeatureStoreException extends RuntimeException {
  public FeatureStoreException(String message) {
    super(message);
  }

  public FeatureStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
