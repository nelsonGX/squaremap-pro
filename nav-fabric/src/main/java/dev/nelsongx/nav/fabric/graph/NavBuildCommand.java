package dev.nelsongx.nav.fabric.graph;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.nelsongx.nav.core.region.RegionGraph;
import dev.nelsongx.nav.fabric.route.ChunkBox;
import dev.nelsongx.nav.fabric.route.RouteService;
import dev.nelsongx.nav.fabric.world.SnapshotWorldView;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /navbuild <radiusChunks>}, {@code /navbuild area <x1> <z1> <x2> <z2>},
 * {@code /navbuild status}, {@code /navbuild cancel}. Requires {@code Commands.LEVEL_GAMEMASTERS}.
 */
// THREADING:
// - register() — whichever thread constructs Commands; only builds dispatcher nodes.
// - Command executors — SERVER THREAD (chat commands are dispatched via server.execute; console and
//   command blocks run on the server thread). They read the source's level key and position, then start a
//   BuildJob with plain values.
// - Progress/completion callbacks — NavExecutor worker. They build the message text from the immutable
//   Progress record and hop to the server thread via onServer(); there the player is re-looked-up by UUID
//   (may have logged out) before sending, and non-player sources are messaged only on the server thread.
public final class NavBuildCommand {

  /** Max radius in chunks. */
  public static final int MAX_RADIUS = 64;
  /** Max chunks for {@code area}. */
  public static final int MAX_AREA_CHUNKS = 16384;
  /** Block coordinate limit (world border maximum). */
  private static final int MAX_BLOCK_COORD = 30_000_000;

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");

  /**
   * Registers the command tree.
   *
   * @param dispatcher dispatcher
   */
  public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(Commands.literal("navbuild")
        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
        .then(Commands.literal("status").executes(this::status))
        .then(Commands.literal("cancel").executes(this::cancel))
        .then(Commands.literal("area")
            .then(Commands.argument("x1", IntegerArgumentType.integer(-MAX_BLOCK_COORD, MAX_BLOCK_COORD))
                .then(Commands.argument("z1", IntegerArgumentType.integer(-MAX_BLOCK_COORD, MAX_BLOCK_COORD))
                    .then(Commands.argument("x2", IntegerArgumentType.integer(-MAX_BLOCK_COORD, MAX_BLOCK_COORD))
                        .then(Commands.argument("z2", IntegerArgumentType.integer(-MAX_BLOCK_COORD, MAX_BLOCK_COORD))
                            .executes(this::area))))))
        .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(1, MAX_RADIUS))
            .executes(this::radius)));
  }

  /** SERVER THREAD. */
  private int radius(CommandContext<CommandSourceStack> ctx) {
    CommandSourceStack source = ctx.getSource();
    int r = IntegerArgumentType.getInteger(ctx, "radiusChunks");
    BlockPos pos = BlockPos.containing(source.getPosition());
    int cx = pos.getX() >> 4;
    int cz = pos.getZ() >> 4;
    return startJob(source, new ChunkBox(cx - r, cz - r, cx + r, cz + r));
  }

  /** SERVER THREAD. */
  private int area(CommandContext<CommandSourceStack> ctx) {
    CommandSourceStack source = ctx.getSource();
    int x1 = IntegerArgumentType.getInteger(ctx, "x1") >> 4;
    int z1 = IntegerArgumentType.getInteger(ctx, "z1") >> 4;
    int x2 = IntegerArgumentType.getInteger(ctx, "x2") >> 4;
    int z2 = IntegerArgumentType.getInteger(ctx, "z2") >> 4;
    ChunkBox box = new ChunkBox(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2),
        Math.max(z1, z2));
    if (box.chunkCount() > MAX_AREA_CHUNKS) {
      source.sendFailure(Component.literal("Area is " + box.chunkCount() + " chunks; max is "
          + MAX_AREA_CHUNKS));
      return 0;
    }
    return startJob(source, box);
  }

  /** SERVER THREAD. */
  private int startJob(CommandSourceStack source, ChunkBox box) {
    NavGraphServices graphs = NavGraphServices.current();
    if (graphs == null) {
      source.sendFailure(Component.literal("Navigation services are not running"));
      return 0;
    }
    ResourceKey<Level> dimension = source.getLevel().dimension();
    String world = RouteService.worldId(dimension);
    if (graphs.job(dimension) != null) {
      source.sendFailure(Component.literal("A build is already running in " + world
          + " (use /navbuild status or /navbuild cancel)"));
      return 0;
    }
    Reporter reporter = new Reporter(source);
    AtomicInteger lastDecile = new AtomicInteger();
    BuildJob<SnapshotWorldView> job;
    try {
      job = graphs.startJob(dimension, box, p -> {
        // THREADING: NavExecutor worker.
        int decile = p.batchCount() == 0 ? 10 : (int) (10L * p.batchesDone() / p.batchCount());
        int prev = lastDecile.get();
        if (decile > prev && decile < 10 && lastDecile.compareAndSet(prev, decile)) {
          reporter.send("Region build " + world + ": " + format(p));
        }
      });
    } catch (RuntimeException e) {
      LOGGER.error("/navbuild failed to start", e);
      source.sendFailure(Component.literal("Build failed to start: " + e.getMessage()));
      return 0;
    }
    if (job == null) {
      source.sendFailure(Component.literal("Cannot build in " + world
          + " (unknown world or a build is already running)"));
      return 0;
    }
    source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
        "Building region graph for %d chunks in %s (chunks %d,%d .. %d,%d)", box.chunkCount(), world,
        box.minChunkX(), box.minChunkZ(), box.maxChunkX(), box.maxChunkZ())), true);
    job.completion().whenComplete((p, err) -> {
      // THREADING: NavExecutor worker (or the thread that cancelled the job).
      if (err != null) {
        LOGGER.warn("/navbuild in {} failed", world, err);
        reporter.send("Region build " + world + " failed: " + rootMessage(err));
      } else {
        reporter.send("Region build " + world + (job.cancelled() && !p.complete() ? " cancelled: "
            : " finished: ") + format(p));
      }
    });
    return 1;
  }

  /** SERVER THREAD. */
  private int status(CommandContext<CommandSourceStack> ctx) {
    CommandSourceStack source = ctx.getSource();
    NavGraphServices graphs = NavGraphServices.current();
    if (graphs == null) {
      source.sendFailure(Component.literal("Navigation services are not running"));
      return 0;
    }
    ResourceKey<Level> dimension = source.getLevel().dimension();
    String world = RouteService.worldId(dimension);
    RegionGraphManager manager = graphs.manager(dimension);
    if (manager == null) {
      source.sendFailure(Component.literal("Unknown world " + world));
      return 0;
    }
    RegionGraph g = manager.current();
    BuildJob<SnapshotWorldView> job = graphs.job(dimension);
    String msg = String.format(Locale.ROOT,
        "%s: %d sectors, %d regions, %d links; dirty %d, waiting %d%s; build: %s", world,
        g.sectors().size(), g.regionCount(), g.linkCount(), manager.dirtyCount(),
        manager.waitingCount(), manager.loading() ? ", loading" : "",
        job == null ? "none" : format(job.progress()));
    source.sendSuccess(() -> Component.literal(msg), false);
    return 1;
  }

  /** SERVER THREAD. */
  private int cancel(CommandContext<CommandSourceStack> ctx) {
    CommandSourceStack source = ctx.getSource();
    NavGraphServices graphs = NavGraphServices.current();
    ResourceKey<Level> dimension = source.getLevel().dimension();
    BuildJob<SnapshotWorldView> job = graphs == null ? null : graphs.job(dimension);
    if (job == null) {
      source.sendFailure(Component.literal("No build running in " + RouteService.worldId(dimension)));
      return 0;
    }
    job.cancel();
    source.sendSuccess(() -> Component.literal("Cancelling build after the current batch"), true);
    return 1;
  }

  private static String format(BuildJob.Progress p) {
    return String.format(Locale.ROOT, "%d built, %d skipped (not loaded), %d/%d chunks, batch %d/%d",
        p.done(), p.skipped(), p.done() + p.skipped(), p.total(), p.batchesDone(), p.batchCount());
  }

  private static String rootMessage(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null && c.getCause() != c) {
      c = c.getCause();
    }
    return String.valueOf(c.getMessage());
  }

  /** Delivers messages to the invoking source on the server thread. */
  // THREADING: constructed on the server thread; send() from ANY THREAD, hops to the server thread.
  private static final class Reporter {
    private final MinecraftServer server;
    private final UUID player;
    private final CommandSourceStack source;

    Reporter(CommandSourceStack source) {
      this.server = source.getServer();
      ServerPlayer p = source.getPlayer();
      this.player = p == null ? null : p.getUUID();
      this.source = p == null ? source : null;
    }

    void send(String text) {
      // MinecraftServer.execute runs tasks inline once the server is stopped; drop unless on the
      // server thread (same guard as NavCommand).
      server.execute(() -> {
        if (!server.isSameThread()) {
          return;
        }
        Component c = Component.literal(text);
        if (player != null) {
          ServerPlayer p = server.getPlayerList().getPlayer(player);
          if (p != null) {
            p.sendSystemMessage(c);
          }
        } else {
          source.sendSystemMessage(c);
        }
      });
    }
  }
}
