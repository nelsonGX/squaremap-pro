package dev.nelsongx.map.core.route;

import dev.nelsongx.map.core.feature.Feature;
import dev.nelsongx.map.core.feature.FeatureData.RailwayData;
import dev.nelsongx.map.core.feature.FeatureData.RoadData;
import dev.nelsongx.map.core.feature.FeatureData.StationData;
import dev.nelsongx.map.core.feature.RoadClass;
import dev.nelsongx.map.core.feature.Vertex;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable road/rail network of one world, built from its features. Buildings are ignored.
 *
 * <ul>
 *   <li><b>Road graph.</b> Nodes are distinct road vertices; a vertex shared by several roads (or
 *       visited twice by one road) is a junction. Edges are consecutive vertices of a road,
 *       bidirectional. Roads that cross without a shared vertex are not connected (bridge/tunnel).
 *   <li><b>Rail graph.</b> One rail node per railway vertex occurrence; trains travel only between
 *       consecutive vertices of the same railway.
 *   <li><b>Stations.</b> A station at point {@code p} lets passengers board or alight any railway
 *       that has a vertex exactly at {@code p} (its own railway and any other railway sharing that
 *       vertex, i.e. an interchange). A railway vertex without a station is not an access point,
 *       even if a road passes through it. Stations whose point is on no railway vertex are ignored.
 * </ul>
 *
 * <p>Per-request links (station transfers, station-to-road links, access/egress) are computed by
 * {@link Router} from {@link RouterOptions}, so one network serves any options.
 *
 * <p>Node ids are assigned in feature-id order, making routing deterministic regardless of the
 * input collection order.
 *
 * <p>THREADING: immutable after {@link #build}; safe to share with any number of routing threads.
 */
public final class Network {
  // ---- road graph ----
  final int[] nodeX;
  final int[] nodeZ;
  /** Segment ids incident to each road node. */
  final int[][] nodeSegments;

  final int[] segA;
  final int[] segB;
  final double[] segLength;
  final String[] segName;
  final RoadClass[] segClass;
  final Grid segmentGrid;
  final Grid roadNodeGrid;

  // ---- rail graph ----
  final String[] railwayName;
  /** Rail nodes of railway r are {@code railStart[r] .. railStart[r + 1] - 1}, in vertex order. */
  final int[] railStart;
  final int[] railX;
  final int[] railZ;
  final int[] railOf;
  /** Stations at each rail node's point. */
  final int[][] railNodeStations;

  // ---- stations ----
  final int[] stationX;
  final int[] stationZ;
  final String[] stationName;
  /** Rail nodes at each station's point. */
  final int[][] stationRailNodes;
  final Grid stationGrid;

  private Network(Builder b) {
    nodeX = b.nodeX.stream().mapToInt(Integer::intValue).toArray();
    nodeZ = b.nodeZ.stream().mapToInt(Integer::intValue).toArray();
    segA = b.segA.stream().mapToInt(Integer::intValue).toArray();
    segB = b.segB.stream().mapToInt(Integer::intValue).toArray();
    segName = b.segName.toArray(String[]::new);
    segClass = b.segClass.toArray(RoadClass[]::new);
    segLength = new double[segA.length];
    Grid.Builder segGrid = new Grid.Builder();
    int[] degree = new int[nodeX.length];
    for (int s = 0; s < segA.length; s++) {
      segLength[s] = Math.hypot(nodeX[segB[s]] - nodeX[segA[s]], nodeZ[segB[s]] - nodeZ[segA[s]]);
      segGrid.addSegment(s, nodeX[segA[s]], nodeZ[segA[s]], nodeX[segB[s]], nodeZ[segB[s]]);
      degree[segA[s]]++;
      degree[segB[s]]++;
    }
    nodeSegments = new int[nodeX.length][];
    for (int n = 0; n < nodeX.length; n++) {
      nodeSegments[n] = new int[degree[n]];
      degree[n] = 0;
    }
    for (int s = 0; s < segA.length; s++) {
      nodeSegments[segA[s]][degree[segA[s]]++] = s;
      nodeSegments[segB[s]][degree[segB[s]]++] = s;
    }
    segmentGrid = segGrid.build();
    Grid.Builder nodeGrid = new Grid.Builder();
    for (int n = 0; n < nodeX.length; n++) {
      nodeGrid.addPoint(n, nodeX[n], nodeZ[n]);
    }
    roadNodeGrid = nodeGrid.build();

    railwayName = b.railwayName.toArray(String[]::new);
    railStart = b.railStart.stream().mapToInt(Integer::intValue).toArray();
    railX = b.railX.stream().mapToInt(Integer::intValue).toArray();
    railZ = b.railZ.stream().mapToInt(Integer::intValue).toArray();
    railOf = b.railOf.stream().mapToInt(Integer::intValue).toArray();

    stationX = b.stationX.stream().mapToInt(Integer::intValue).toArray();
    stationZ = b.stationZ.stream().mapToInt(Integer::intValue).toArray();
    stationName = b.stationName.toArray(String[]::new);
    stationRailNodes = b.stationRailNodes.toArray(int[][]::new);
    List<List<Integer>> atRail = new ArrayList<>();
    for (int i = 0; i < railX.length; i++) {
      atRail.add(new ArrayList<>());
    }
    Grid.Builder stGrid = new Grid.Builder();
    for (int s = 0; s < stationX.length; s++) {
      stGrid.addPoint(s, stationX[s], stationZ[s]);
      for (int rn : stationRailNodes[s]) {
        atRail.get(rn).add(s);
      }
    }
    stationGrid = stGrid.build();
    railNodeStations = new int[railX.length][];
    for (int i = 0; i < railX.length; i++) {
      railNodeStations[i] = atRail.get(i).stream().mapToInt(Integer::intValue).toArray();
    }
  }

  /** Builds the network of one world from its features (other feature types are ignored). */
  public static Network build(Collection<Feature> features) {
    List<Feature> sorted = new ArrayList<>(features);
    sorted.sort(Comparator.comparing(Feature::id));
    Builder b = new Builder();
    for (Feature f : sorted) {
      if (f.data() instanceof RoadData road) {
        b.addRoad(road);
      }
    }
    Map<Vertex, List<Integer>> railNodesAt = new HashMap<>();
    for (Feature f : sorted) {
      if (f.data() instanceof RailwayData railway) {
        b.addRailway(railway, railNodesAt);
      }
    }
    b.railStart.add(b.railX.size());
    for (Feature f : sorted) {
      if (f.data() instanceof StationData station) {
        b.addStation(station, railNodesAt);
      }
    }
    return new Network(b);
  }

  public int roadNodeCount() {
    return nodeX.length;
  }

  public int roadSegmentCount() {
    return segA.length;
  }

  public int railwayCount() {
    return railwayName.length;
  }

  public int stationCount() {
    return stationX.length;
  }

  private static final class Builder {
    final Map<Vertex, Integer> nodeIndex = new HashMap<>();
    final List<Integer> nodeX = new ArrayList<>();
    final List<Integer> nodeZ = new ArrayList<>();
    final List<Integer> segA = new ArrayList<>();
    final List<Integer> segB = new ArrayList<>();
    final List<String> segName = new ArrayList<>();
    final List<RoadClass> segClass = new ArrayList<>();

    final List<String> railwayName = new ArrayList<>();
    final List<Integer> railStart = new ArrayList<>();
    final List<Integer> railX = new ArrayList<>();
    final List<Integer> railZ = new ArrayList<>();
    final List<Integer> railOf = new ArrayList<>();

    final List<Integer> stationX = new ArrayList<>();
    final List<Integer> stationZ = new ArrayList<>();
    final List<String> stationName = new ArrayList<>();
    final List<int[]> stationRailNodes = new ArrayList<>();

    int node(Vertex v) {
      return nodeIndex.computeIfAbsent(
          v,
          k -> {
            nodeX.add(k.x());
            nodeZ.add(k.z());
            return nodeX.size() - 1;
          });
    }

    void addRoad(RoadData road) {
      List<Vertex> line = road.line();
      if (line == null || road.roadClass() == null) {
        return;
      }
      for (int i = 1; i < line.size(); i++) {
        Vertex a = line.get(i - 1);
        Vertex c = line.get(i);
        if (a == null || c == null || a.equals(c)) {
          continue;
        }
        segA.add(node(a));
        segB.add(node(c));
        segName.add(road.name());
        segClass.add(road.roadClass());
      }
    }

    void addRailway(RailwayData railway, Map<Vertex, List<Integer>> railNodesAt) {
      List<Vertex> line = railway.line();
      if (line == null) {
        return;
      }
      for (Vertex v : line) {
        if (v == null) { // List.copyOf lists throw on contains(null)
          return;
        }
      }
      int r = railwayName.size();
      railwayName.add(railway.name());
      railStart.add(railX.size());
      for (Vertex v : line) {
        int id = railX.size();
        railX.add(v.x());
        railZ.add(v.z());
        railOf.add(r);
        railNodesAt.computeIfAbsent(v, k -> new ArrayList<>()).add(id);
      }
    }

    void addStation(StationData station, Map<Vertex, List<Integer>> railNodesAt) {
      if (station.point() == null) {
        return;
      }
      List<Integer> nodes = railNodesAt.get(station.point());
      if (nodes == null) {
        return;
      }
      stationX.add(station.point().x());
      stationZ.add(station.point().z());
      stationName.add(station.name());
      stationRailNodes.add(nodes.stream().mapToInt(Integer::intValue).toArray());
    }
  }
}
