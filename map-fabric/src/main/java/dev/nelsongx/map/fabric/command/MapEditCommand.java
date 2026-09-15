package dev.nelsongx.map.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.nelsongx.map.core.feature.Actor;
import dev.nelsongx.map.fabric.auth.AuthServices;
import dev.nelsongx.map.fabric.auth.FabricPermissionChecker;
import dev.nelsongx.map.fabric.auth.PermissionChecker;
import dev.nelsongx.map.fabric.auth.TokenStore;
import dev.nelsongx.map.fabric.http.HttpConfig;
import dev.nelsongx.map.fabric.http.HttpLifecycle;
import java.util.Objects;
import java.util.function.Supplier;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

/**
 * {@code /mapedit} — the mod's only command. Sends the executing player a clickable single-use link to
 * the web editor.
 */
// THREADING: register() runs during Fabric's CommandRegistrationCallback; the command body runs on the
// server thread (Brigadier dispatch). It only reads volatile state (auth services, HTTP running info)
// and the synchronized in-memory TokenStore — no I/O, no blocking.
public final class MapEditCommand {

  /** Command literal. */
  public static final String NAME = "mapedit";

  private MapEditCommand() {
  }

  /**
   * Registers {@code /mapedit}.
   *
   * @param dispatcher command dispatcher
   * @param auth supplies the running server's auth services (null when none)
   * @param http supplies the running HTTP server info (null when not running)
   */
  public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
      Supplier<AuthServices> auth, Supplier<HttpLifecycle.Running> http) {
    Objects.requireNonNull(auth, "auth");
    Objects.requireNonNull(http, "http");
    dispatcher.register(Commands.literal(NAME)
        .requires(Permissions.require(PermissionChecker.EDIT_NODE,
            FabricPermissionChecker.DEFAULT_LEVEL))
        .executes(ctx -> run(ctx.getSource(), auth.get(), http.get())));
  }

  private static int run(CommandSourceStack source, AuthServices auth,
      HttpLifecycle.Running http) {
    ServerPlayer player = source.getPlayer();
    if (player == null) {
      source.sendFailure(Component.literal("/" + NAME + " can only be used by a player."));
      return 0;
    }
    if (auth == null || http == null) {
      source.sendFailure(Component.literal(
          "The map web server is not running; ask the server admin to check the squaremap-pro config."));
      return 0;
    }
    NameAndId profile = player.nameAndId();
    TokenStore tokens = auth.tokens();
    String token = tokens.issue(new Actor(profile.id(), profile.name()));
    HttpConfig config = http.config();
    EditorLinks.Link link = EditorLinks.redeemLink(config.publicUrl(), config.bind(), http.port(),
        token);

    long minutes = tokens.ttl().toMinutes();
    MutableComponent button = Component.literal("[Open map editor]").withStyle(style -> style
        .withColor(ChatFormatting.GREEN)
        .withUnderlined(true)
        .withClickEvent(new ClickEvent.OpenUrl(link.uri()))
        .withHoverEvent(new HoverEvent.ShowText(
            Component.literal("Link expires in " + minutes + " minutes"))));
    MutableComponent message = Component.empty().append(button);
    if (!link.usedPublicUrl()) {
      message.append(Component.literal(
              "\nhttp.publicUrl is not set, so this link uses the server's local address and may not "
                  + "open for you. Ask the server admin to set http.publicUrl in "
                  + "config/" + HttpConfig.FILE_NAME + ".")
          .withStyle(ChatFormatting.YELLOW));
    }
    // Sent straight to the player: sendSuccess would be suppressed by the sendCommandFeedback gamerule
    // (ServerPlayer's source acceptsSuccess() reads it) and could broadcast the token-bearing link to
    // other ops / the server log.
    player.sendSystemMessage(message);
    return 1;
  }
}
