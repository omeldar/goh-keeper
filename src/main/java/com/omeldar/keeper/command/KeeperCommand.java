package com.omeldar.keeper.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.omeldar.keeper.Config;
import com.omeldar.keeper.KeeperServer;
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
                                .then(argument("top", IntegerArgumentType.integer(1, 25))
                                        .executes(ctx -> lag(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "top")))))
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
        if (cfg.backupsEnabled) {
            reply(source, "  backups every " + cfg.backupEveryMinutes + " min, keeping "
                    + cfg.backupKeep + (Backups.get().busy() ? " (one running now)" : ""),
                    Formatting.GRAY);
        } else {
            reply(source, "  backups are off (backupsEnabled in keeper.json)", Formatting.GRAY);
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
