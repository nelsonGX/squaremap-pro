package dev.nelsongx.map.core.feature;

import java.util.Objects;

/**
 * One validation failure. {@code field} is a Feature API v1 JSON field name: {@code type},
 * {@code name}, {@code geometry}, {@code category}, {@code description}, {@code roadClass},
 * {@code colour} or {@code railwayId}.
 */
public record ValidationError(String field, String message) {
  public ValidationError {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(message, "message");
  }
}
