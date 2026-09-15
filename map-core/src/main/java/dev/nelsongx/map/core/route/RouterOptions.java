package dev.nelsongx.map.core.route;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Per-request routing options. All distances are in blocks.
 *
 * <p>{@code modes} controls whether {@link Mode#ROAD} and {@link Mode#RAIL} may be used. {@link
 * Mode#WALK} is <em>always</em> allowed (access/egress, transfers and the direct walk), whether or
 * not it is in the set; limit walking with {@code maxAccessWalk} and {@code maxDirectWalk}.
 *
 * <p>THREADING: immutable value.
 *
 * @param maxAccessWalk max straight-line walk from the start to the network (and from the network
 *     to the goal)
 * @param accessCandidates number of nearest road segments considered for access and egress
 * @param transferWalk max walk between two stations
 * @param stationRoadLink max walk between a station and its nearest road vertex
 * @param maxDirectWalk max length of the direct start-to-goal walk ({@code +Infinity} = always
 *     available, so a route is always found)
 */
public record RouterOptions(
    Speeds speeds,
    Set<Mode> modes,
    double maxAccessWalk,
    int accessCandidates,
    double transferWalk,
    double stationRoadLink,
    double maxDirectWalk) {

  public static final double DEFAULT_MAX_ACCESS_WALK = 256;
  public static final int DEFAULT_ACCESS_CANDIDATES = 4;
  public static final double DEFAULT_TRANSFER_WALK = 32;
  public static final double DEFAULT_STATION_ROAD_LINK = 32;

  public RouterOptions {
    Objects.requireNonNull(speeds, "speeds");
    Objects.requireNonNull(modes, "modes");
    modes = Collections.unmodifiableSet(modes.isEmpty() ? EnumSet.noneOf(Mode.class) : EnumSet.copyOf(modes));
    checkDistance("maxAccessWalk", maxAccessWalk);
    checkDistance("transferWalk", transferWalk);
    checkDistance("stationRoadLink", stationRoadLink);
    checkDistance("maxDirectWalk", maxDirectWalk);
    if (accessCandidates < 0) {
      throw new IllegalArgumentException("accessCandidates must be >= 0: " + accessCandidates);
    }
  }

  /** Default speeds, all modes, 256 / 4 / 32 / 32, unlimited direct walk. */
  public static RouterOptions defaults() {
    return new RouterOptions(
        Speeds.defaults(),
        EnumSet.allOf(Mode.class),
        DEFAULT_MAX_ACCESS_WALK,
        DEFAULT_ACCESS_CANDIDATES,
        DEFAULT_TRANSFER_WALK,
        DEFAULT_STATION_ROAD_LINK,
        Double.POSITIVE_INFINITY);
  }

  public RouterOptions withSpeeds(Speeds speeds) {
    return new RouterOptions(
        speeds, modes, maxAccessWalk, accessCandidates, transferWalk, stationRoadLink, maxDirectWalk);
  }

  public RouterOptions withModes(Set<Mode> modes) {
    return new RouterOptions(
        speeds, modes, maxAccessWalk, accessCandidates, transferWalk, stationRoadLink, maxDirectWalk);
  }

  public RouterOptions withMaxAccessWalk(double maxAccessWalk) {
    return new RouterOptions(
        speeds, modes, maxAccessWalk, accessCandidates, transferWalk, stationRoadLink, maxDirectWalk);
  }

  public RouterOptions withAccessCandidates(int accessCandidates) {
    return new RouterOptions(
        speeds, modes, maxAccessWalk, accessCandidates, transferWalk, stationRoadLink, maxDirectWalk);
  }

  public RouterOptions withTransferWalk(double transferWalk) {
    return new RouterOptions(
        speeds, modes, maxAccessWalk, accessCandidates, transferWalk, stationRoadLink, maxDirectWalk);
  }

  public RouterOptions withStationRoadLink(double stationRoadLink) {
    return new RouterOptions(
        speeds, modes, maxAccessWalk, accessCandidates, transferWalk, stationRoadLink, maxDirectWalk);
  }

  public RouterOptions withMaxDirectWalk(double maxDirectWalk) {
    return new RouterOptions(
        speeds, modes, maxAccessWalk, accessCandidates, transferWalk, stationRoadLink, maxDirectWalk);
  }

  boolean roadAllowed() {
    return modes.contains(Mode.ROAD);
  }

  boolean railAllowed() {
    return modes.contains(Mode.RAIL);
  }

  private static void checkDistance(String name, double value) {
    if (!(value >= 0)) {
      throw new IllegalArgumentException(name + " must be >= 0: " + value);
    }
  }
}
