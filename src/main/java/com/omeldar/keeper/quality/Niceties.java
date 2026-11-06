package com.omeldar.keeper.quality;

import com.omeldar.keeper.Config;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The small things, and a clear line about what they are.
 *
 * <p>Each of these tells a player something the game already knew and did not mention. Where
 * you died, how many people are in bed, how long you have played. None of it changes an
 * outcome: the death happened either way, the night passes on the same vanilla percentage,
 * the playtime was already in your statistics screen.
 *
 * <p>The line is worth drawing because it is easy to cross by accident. A death-coordinates
 * message is information. A one-click "return to where I died" would be a mechanic, and a
 * fairly large one - so it is not here.
 */
public final class Niceties {
    private int lastSleeping = -1;

    private Niceties() {}

    private static final Niceties INSTANCE = new Niceties();

    public static Niceties get() {
        return INSTANCE;
    }

    /** Called when a player dies, before they respawn. */
    public void onDeath(ServerPlayerEntity player) {
        if (!Config.get().deathCoordinates) return;
        String where = player.getEntityWorld().getRegistryKey().getValue().toString();
        if (where.startsWith("minecraft:")) where = where.substring(10);
        player.sendMessage(Text.literal("you died at ").formatted(Formatting.GRAY)
                .append(Text.literal(String.format("%d %d %d",
                                player.getBlockX(), player.getBlockY(), player.getBlockZ()))
                        .formatted(Formatting.WHITE))
                .append(Text.literal(" in " + where).formatted(Formatting.GRAY)), false);
    }

    public void onJoin(MinecraftServer server, ServerPlayerEntity player) {
        if (!Config.get().joinPlaytime) return;
        try {
            int ticks = player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.PLAY_TIME));
            long hours = ticks / 20L / 3600L;
            if (hours >= 1) {
                player.sendMessage(Text.literal("welcome back - " + hours + " hours on this world")
                        .formatted(Formatting.DARK_GRAY), false);
            }
        } catch (Exception ignored) {
            // The statistics file can be missing or new; a greeting is not worth an error.
        }
    }

    /**
     * Report sleeping progress when it changes. Vanilla already decides when the night
     * passes, from the playersSleepingPercentage game rule; this only says where the count
     * currently stands, which is the thing everybody otherwise asks in chat.
     */
    public void onTick(MinecraftServer server) {
        if (!Config.get().sleepProgress) return;
        if (server.getTicks() % 20 != 0) return;

        int sleeping = 0;
        int awakeAndCounted = 0;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.isSpectator()) continue;
            awakeAndCounted++;
            if (p.isSleeping()) sleeping++;
        }

        if (sleeping == lastSleeping) return;
        int before = lastSleeping;
        lastSleeping = sleeping;
        if (sleeping == 0 || before < 0) return;

        // Deliberately not computing how many it takes. That is the playersSleepingPercentage
        // rule, and 1.21.11 moved reading a rule value behind an API that is not worth a
        // reflection hack for a status line - especially since vanilla announces the skip
        // itself the moment the threshold is met. The count is the part people ask for.
        if (sleeping >= awakeAndCounted) return;
        server.getPlayerManager().broadcast(
                Text.literal(sleeping + " of " + awakeAndCounted + " in bed")
                        .formatted(Formatting.DARK_GRAY), false);
    }
}
