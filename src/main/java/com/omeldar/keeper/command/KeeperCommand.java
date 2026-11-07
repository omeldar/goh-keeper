package com.omeldar.keeper.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.omeldar.keeper.Config;
import com.omeldar.keeper.KeeperServer;
import com.omeldar.keeper.backup.BackupSwitch;
import com.omeldar.keeper.backup.Backups;
import com.omeldar.keeper.perf.LagReport;
import com.omeldar.keeper.perf.TickWatch;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Path;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * {@code /keeper}. The read-only parts are open to everyone, because a player who is being
 * told to move their farm deserves to see the same numbers the admin saw. Backups need the
 * usual operator level.
 */
public final class KeeperCommand {
    private KeeperCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) ->
                dispatcher.register(literal("keeper")
                        .executes(ctx -> status(ctx.getSource()))
                        .then(literal("status").executes(ctx -> status(ctx.getSource())))
                        .then(literal("lag")
                                .executes(ctx -> lag(ctx.getSource(), 5))
                                .then(literal("top")
                                        .executes(ctx -> lag(ctx.getSource(), 10))
                                        .then(argument("n", IntegerArgumentType.integer(1, 25))
                                                .executes(ctx -> lag(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "n")))))
                                // Not called "top": Brigadier keys children by name, so an argument
                                // sharing a name with the literal above silently merges into it.
                                .then(argument("rows", IntegerArgumentType.integer(1, 25))
                                        .executes(ctx -> lag(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "rows")))))
                        .then(literal("afk").executes(ctx -> afk(ctx.getSource())))
                        .then(literal("reload")
                                .requires(KeeperCommand::isAdmin)
                                .executes(ctx -> {
                                    Config.load();
                                    reply(ctx.getSource(), "config reloaded", Formatting.GREEN);
                                    return 1;
                                }))
                        .then(literal("backup")
                                .requires(KeeperCommand::isAdmin)
                                .executes(ctx -> backupState(ctx.getSource()))
                                .then(literal("on")
                                        .requires(KeeperCommand::isOwner)
                                        .executes(ctx -> backupPreview(ctx.getSource()))
                                        .then(literal("confirm")
                                                .executes(ctx -> backupSwitch(ctx.getSource(), true))))
                                .then(literal("off")
                                        .executes(ctx -> backupSwitch(ctx.getSource(), false)))
                                .then(literal("now").executes(ctx -> backupNow(ctx.getSource())))
                                .then(literal("list").executes(ctx -> backupList(ctx.getSource()))))));
    }

    /**
     * Operator level 3 and up. 1.21.11 replaced the plain integer level with a permission
     * predicate, so the check asks the source's own predicate rather than comparing numbers.
     */
    private static boolean isAdmin(ServerCommandSource source) {
        return source.getPermissions()
                .hasPermission(new Permission.Level(PermissionLevel.ADMINS));
    }

    /**
     * Operator level 4, the server owner. Turning on something that writes copies of the
     * whole world to disk is an owner's decision, not a moderator's.
     */
    private static boolean isOwner(ServerCommandSource source) {
        return source.getPermissions()
                .hasPermission(new Permission.Level(PermissionLevel.OWNERS));
    }

    // ----------------------------------------------------------------- actions

    private static int status(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        TickWatch t = KeeperServer.ticks();

        reply(source, "keeper", Formatting.AQUA);
        reply(source, String.format("  %.1f ms/tick now, %.1f over a minute, %.1f over five",
                t.mspt5s(), t.mspt1m(), t.mspt5m()), colourFor(t.mspt1m()));
        reply(source, String.format("  that is %.1f tps; the budget is 50 ms a tick",
                TickWatch.tps(t.mspt1m())), Formatting.GRAY);
        if (t.worstMspt() > 0) {
            reply(source, String.format("  worst single tick since start: %.0f ms",
                    t.worstMspt()), Formatting.GRAY);
        }

        int loaded = 0;
        for (var world : server.getWorlds()) loaded += world.getChunkManager().getLoadedChunkCount();
        reply(source, "  " + server.getPlayerManager().getCurrentPlayerCount() + " online, "
                + KeeperServer.afk().afkCount() + " afk, " + loaded + " chunks loaded",
                Formatting.GRAY);

        Config cfg = Config.get();
        if (BackupSwitch.on(server)) {
            reply(source, "  backups every " + cfg.backupEveryMinutes + " min, keeping "
                    + cfg.backupKeep + (Backups.get().busy() ? " (one running now)" : ""),
                    Formatting.GRAY);
        } else {
            reply(source, "  backups are switched off (/keeper backup on)", Formatting.GRAY);
        }
        reply(source, "  /keeper lag to see where the time is going", Formatting.DARK_GRAY);
        return 1;
    }

    private static int lag(ServerCommandSource source, int topN) {
        MinecraftServer server = source.getServer();
        TickWatch t = KeeperServer.ticks();
        reply(source, String.format("%.1f ms/tick over the last minute", t.mspt1m()),
                colourFor(t.mspt1m()));

        long started = System.nanoTime();
        LagReport report = LagReport.take(server, topN);
        for (String line : report.lines) {
            reply(source, line, line.startsWith("  ") ? Formatting.GRAY : Formatting.WHITE);
        }
        reply(source, String.format("(census took %.0f ms, and only runs when asked)",
                (System.nanoTime() - started) / 1_000_000.0), Formatting.DARK_GRAY);
        return 1;
    }

    private static int afk(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        int shown = 0;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            int idle = KeeperServer.afk().idleMinutes(server, p);
            if (KeeperServer.afk().isAfk(p)) {
                reply(source, "  " + p.getName().getString() + " - afk " + idle + " min",
                        Formatting.GRAY);
                shown++;
            }
        }
        if (shown == 0) reply(source, "everyone online is active", Formatting.GRAY);
        return 1;
    }

    private static String backupDir(MinecraftServer server) {
        Path dir = Path.of(Config.get().backupDir);
        if (!dir.isAbsolute()) dir = server.getRunDirectory().resolve(dir);
        return dir.normalize().toString();
    }

    private static int backupState(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        if (BackupSwitch.on(server)) {
            reply(source, "backups are on, switched on by " + BackupSwitch.who(server),
                    Formatting.GREEN);
            reply(source, "  /keeper backup off to stop them", Formatting.DARK_GRAY);
        } else {
            reply(source, "backups are switched off", Formatting.GRAY);
            reply(source, "  /keeper backup on to see what turning them on would do",
                    Formatting.DARK_GRAY);
        }
        return 1;
    }

    private static int backupPreview(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        Config cfg = Config.get();
        if (BackupSwitch.on(server)) return backupState(source);
        reply(source, "turning backups on will:", Formatting.AQUA);
        reply(source, "  zip this world every " + cfg.backupEveryMinutes + " min"
                + (cfg.skipBackupIfIdle ? ", skipping a run when nobody has played since the last"
                : ""), Formatting.GRAY);
        reply(source, "  write them to " + backupDir(server) + " and keep the newest "
                + cfg.backupKeep + ", each about the size of the world folder", Formatting.GRAY);
        reply(source, "  pause world saving for the length of each copy", Formatting.GRAY);
        reply(source, "/keeper backup on confirm to switch them on", Formatting.YELLOW);
        return 1;
    }

    private static int backupSwitch(ServerCommandSource source, boolean on) {
        MinecraftServer server = source.getServer();
        if (BackupSwitch.on(server) == on) {
            reply(source, "backups are already " + (on ? "on" : "off"), Formatting.GRAY);
            return 1;
        }
        String failed = BackupSwitch.set(server, on, source.getName());
        if (failed != null) {
            reply(source, failed, Formatting.RED);
            return 0;
        }
        reply(source, on ? "backups are on. The first runs " + Config.get().backupEveryMinutes
                + " min from now, or now with /keeper backup now" : "backups are off",
                on ? Formatting.GREEN : Formatting.YELLOW);
        return 1;
    }

    private static int backupNow(ServerCommandSource source) {
        String refusal = Backups.get().start(source.getServer(),
                "asked for by " + source.getName());
        if (refusal != null) {
            reply(source, "not now: " + refusal, Formatting.YELLOW);
        } else {
            reply(source, "backup started; the server keeps running", Formatting.GREEN);
        }
        return 1;
    }

    private static int backupList(ServerCommandSource source) {
        for (String line : Backups.get().list(source.getServer())) {
            reply(source, "  " + line, Formatting.GRAY);
        }
        return 1;
    }

    // ---------------------------------------------------------------- plumbing

    private static Formatting colourFor(double mspt) {
        if (mspt >= 50) return Formatting.RED;
        if (mspt >= 35) return Formatting.YELLOW;
        return Formatting.GREEN;
    }

    private static void reply(ServerCommandSource source, String message, Formatting colour) {
        source.sendFeedback(() -> Text.literal(message).formatted(colour), false);
    }
}
