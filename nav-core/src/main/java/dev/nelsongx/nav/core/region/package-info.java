/**
 * Coarse region graph for hierarchical search: sectors (square block columns) are split into regions
 * (mutually connected components of standing positions), joined by directed links that are exactly the
 * {@link dev.nelsongx.nav.core.path.MovementModel} moves between different regions. Includes SQLite
 * serialization ({@link dev.nelsongx.nav.core.region.RegionGraphStore}).
 */
package dev.nelsongx.nav.core.region;
