package dev.nelsongx.map.core.feature;

import java.util.Optional;

/** Resolves a railway feature id (in the same world) to its data. Supplied by the store. */
@FunctionalInterface
public interface RailwayLookup {
  Optional<FeatureData.RailwayData> railway(String id);
}
