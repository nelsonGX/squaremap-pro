package dev.nelsongx.nav.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.nelsongx.nav.fabric.route.RouteOutcome;
import dev.nelsongx.nav.fabric.route.RouteService;
import dev.nelsongx.nav.fabric.squaremap.NavMapLayer;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@code /nav <x> <z>} and {@code /nav clear}. */
// THREADING:
// - Command executors (navigate, clear) — SERVER THREAD: chat commands are dispatched via
//   ServerGamePacketListenerImpl.tryHandleChat → server.execute(runnable). They read the player's UUID,
//   dimension and feet position there, then hand plain values to RouteService (never the player/level).
// - Route completion callbacks — NavExecutor worker (or inline for fast failures). They only touch the
//   in-flight set (concurrent) and hop to the server thread via server.execute for every player lookup,
//   message and map-layer update.
public final class NavCommand {

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");

  private final RouteService routes;
  private final NavMapLayer mapLayer;
  private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

  /**
   * @param routes route service
   * @param mapLayer map layer for drawing routes
   */
  public NavCommand(RouteService routes, NavMapLayer mapLayer) {
    this.routes = Objects.requireNonNull(routes, "routes");
    this.mapLayer = Objects.requireNonNull(mapLayer, "mapLayer");
  }

  /**
   * Registers the command tree. Called from {@code CommandRegistrationCallback} (whichever thread
   * constructs {@code Commands}); only builds dispatcher nodes, touches no game state.
   *
   * @param dispatcher dispatcher
   */
  public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(Commands.literal("nav")
        .then(Commands.literal("clear").executes(this::clear))
        .then(Commands.argument("x", IntegerArgumentType.integer())
            .then(Commands.argument("z", IntegerArgumentType.integer())
                .executes(this::navigate))));
  }

  /** SERVER THREAD. */
  private int navigate(CommandContext<CommandSourceStack> ctx) {
    CommandSourceStack source = ctx.getSource();
    ServerPlayer player = source.getPlayer();
    if (player == null) {
      source.sendFailure(Component.literal("Only players can use /nav"));
      return 0;
    }
    int toX = IntegerArgumentType.getInteger(ctx, "x");
    int toZ = IntegerArgumentType.getInteger(ctx, "z");
    UUID uuid = player.getUUID();
    String playerName = player.getScoreboardName();
    ResourceKey<Level> dimension = player.level().dimension();
    BlockPos feet = player.blockPosition();
    MinecraftServer server = source.getServer();

    if (!inFlight.add(uuid)) {
      source.sendFailure(Component.literal("Already calculating a route, please wait"));
      return 0;
    }
    source.sendSuccess(() -> Component.literal("Calculating route..."), false);
    CompletableFuture<RouteOutcome> future;
    try {
      future = routes.route(dimension, feet.getX(), OptionalInt.of(feet.getY()), feet.getZ(), toX,
          toZ);
    } catch (RuntimeException e) {
      inFlight.remove(uuid);
      LOGGER.error("/nav failed to start", e);
      source.sendFailure(Component.literal("Route failed: " + e.getMessage()));
      return 0;
    }
    // THREADING: this callback runs on a NavExecutor worker (or inline if already complete).
    // The UUID is released here, before the server hop, because onServer() drops its runnable once
    // the server is stopping — releasing it inside that runnable could leave it stuck.
    future.whenComplete((outcome, err) -> {
      inFlight.remove(uuid);
      RouteOutcome o = outcome != null ? outcome
          : RouteOutcome.notReady(RouteService.worldId(dimension),
              "internal error: " + err);
      onServer(server, () -> deliver(server, uuid, playerName, o));
    });
    return 1;
  }

  /**
   * Forgets all in-flight requests. This instance lives for the whole JVM (integrated servers can
   * start several times), so call on SERVER_STOPPING. ANY THREAD.
   */
  public void clearInFlight() {
    inFlight.clear();
  }

  /** SERVER THREAD. Re-looks-up the player (they may have logged out) and reports. */
  private void deliver(MinecraftServer server, UUID uuid, String playerName, RouteOutcome o) {
    ServerPlayer player = server.getPlayerList().getPlayer(uuid);
    if (player == null) {
      return;
    }
    String msg = switch (o.status()) {
      case OK -> {
        String base = String.format(Locale.ROOT, "Route: %d blocks, %d waypoints",
            Math.round(o.distance()), o.points().size());
        if (!mapLayer.available()) {
          yield base + " (squaremap not installed — route not drawn)";
        }
        boolean drawn;
        try {
          drawn = mapLayer.showRoute(o.world(), uuid, playerName, o.points(), o.distance());
        } catch (RuntimeException | LinkageError e) {
          LOGGER.warn("failed to draw route for {}", playerName, e);
          drawn = false;
        }
        yield base + (drawn ? " — drawn on map" : " (route not drawn: world not on map)");
      }
      case NO_PATH -> "No path found";
      case CAP_EXCEEDED -> "Search limit reached — try a closer destination";
      case INVALID_REQUEST, WORLD_NOT_FOUND, NOT_READY -> o.error();
    };
    player.sendSystemMessage(Component.literal(msg));
  }

  /** SERVER THREAD. */
  private int clear(CommandContext<CommandSourceStack> ctx) {
    CommandSourceStack source = ctx.getSource();
    ServerPlayer player = source.getPlayer();
    if (player == null) {
      source.sendFailure(Component.literal("Only players can use /nav"));
      return 0;
    }
    UUID uuid = player.getUUID();
    for (ServerLevel level : source.getServer().getAllLevels()) {
      mapLayer.clearRoute(RouteService.worldId(level.dimension()), uuid);
    }
    source.sendSuccess(() -> Component.literal("Route cleared"), false);
    return 1;
  }

  /**
   * Runs {@code task} on the server thread. {@code MinecraftServer.execute} runs the task inline on the
   * caller once the server is stopped ({@code scheduleExecutables() = super.scheduleExecutables() &&
   * !isStopped()}), so the task is dropped unless it really is on the server thread. ANY THREAD.
   */
  private static void onServer(MinecraftServer server, Runnable task) {
    server.execute(() -> {
      if (server.isSameThread()) {
        task.run();
      }
    });
  }
}
