package com.omeldar.keeper.afk;

import com.omeldar.keeper.Config;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is actually at the keyboard.
 *
 * <p>Useful to know and harmless to say. It is deliberately only an announcement: plenty of
 * mods use afk status to move a player out of the mob spawning calculation or to stop their
 * chunks ticking, and both of those change what the game does - an afk farm that keeps
 * running is a mechanic, and whether it should is not this mod's call.
 *
 * <p>Idle means the player has not moved or turned. That catches someone who walked away and
 * leaves someone who is standing still mining alone, since mining turns the view.
 */
public final class AfkWatch {
    private static final int CHECK_EVERY_TICKS = 40;

    private record Seen(double x, double y, double z, float yaw, float pitch, long atTick) {}

    private final Map<UUID, Seen> lastMoved = new HashMap<>();
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();

    public void onTick(MinecraftServer server) {
        if (server.getTicks() % CHECK_EVERY_TICKS != 0) return;
        Config cfg = Config.get();
        long idleTicks = Math.max(1, cfg.afkMinutes) * 60L * 20L;
        long now = server.getTicks();

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            UUID id = p.getUuid();
            Seen before = lastMoved.get(id);
            boolean moved = before == null
                    || Math.abs(before.x - p.getX()) > 0.05
                    || Math.abs(before.y - p.getY()) > 0.05
                    || Math.abs(before.z - p.getZ()) > 0.05
                    || Math.abs(before.yaw - p.getYaw()) > 0.5
                    || Math.abs(before.pitch - p.getPitch()) > 0.5;

            if (moved) {
                lastMoved.put(id, new Seen(p.getX(), p.getY(), p.getZ(),
                        p.getYaw(), p.getPitch(), now));
                if (afk.remove(id) && cfg.announceAfk) {
                    announce(server, p, " is back");
                }
            } else if (now - before.atTick() >= idleTicks && afk.add(id) && cfg.announceAfk) {
                announce(server, p, " is afk");
            }
        }

        // Someone who logged out is not afk, they are gone.
        lastMoved.keySet().removeIf(id -> server.getPlayerManager().getPlayer(id) == null);
        afk.removeIf(id -> server.getPlayerManager().getPlayer(id) == null);
    }

    public boolean isAfk(ServerPlayerEntity p) {
        return afk.contains(p.getUuid());
    }

    public int afkCount() {
        return afk.size();
    }

    /** Minutes since this player last moved, or 0 if they just did. */
    public int idleMinutes(MinecraftServer server, ServerPlayerEntity p) {
        Seen seen = lastMoved.get(p.getUuid());
        if (seen == null) return 0;
        return (int) ((server.getTicks() - seen.atTick()) / (60 * 20));
    }

    private static void announce(MinecraftServer server, ServerPlayerEntity p, String what) {
        server.getPlayerManager().broadcast(
                Text.literal(p.getName().getString()).formatted(Formatting.GRAY)
                        .append(Text.literal(what).formatted(Formatting.DARK_GRAY)),
                false);
    }
}
