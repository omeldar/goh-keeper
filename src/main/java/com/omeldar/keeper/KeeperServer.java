package com.omeldar.keeper;

import com.omeldar.keeper.afk.AfkWatch;
import com.omeldar.keeper.backup.Backups;
import com.omeldar.keeper.command.KeeperCommand;
import com.omeldar.keeper.perf.TickWatch;
import com.omeldar.keeper.quality.Niceties;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeper: a caretaker for a server that is meant to stay vanilla.
 *
 * <p>The rule the whole mod is held to is that it changes no game mechanic. Nothing here
 * alters a drop, a spawn, a growth rate, a recipe or a rule. What it does is watch, report and
 * do the chores: say where the lag is actually coming from, take a backup that will restore,
 * note who has wandered off, and tell a player the coordinate they just lost.
 *
 * <p>That restraint is the feature. A survival world is worth playing because its rules are
 * the ones everybody already knows, and the moment a server mod starts improving the game
 * nobody can tell what is vanilla any more. So this one improves the <em>server</em>.
 *
 * <p>It is also built to be cheap. The tick hook is a modulo and a return; the expensive
 * reports only run when a command asks for them.
 */
public final class KeeperServer implements ModInitializer {
    public static final String MOD_ID = "keeper";
    public static final Logger LOG = LoggerFactory.getLogger("keeper");

    private static final TickWatch TICKS = new TickWatch();
    private static final AfkWatch AFK = new AfkWatch();

    public static TickWatch ticks() {
        return TICKS;
    }

    public static AfkWatch afk() {
        return AFK;
    }

    @Override
    public void onInitialize() {
        Config.load();
        KeeperCommand.register();

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            TICKS.onTick(server);
            AFK.onTick(server);
            Niceties.get().onTick(server);
            Backups.get().onTick(server);
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayerEntity player) {
                Niceties.get().onDeath(player);
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            Backups.get().noteActivity();
            Niceties.get().onJoin(server, handler.getPlayer());
        });

        LOG.info("keeper ready - /keeper status");
    }
}
