package com.omeldar.keeper.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.omeldar.keeper.KeeperServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Whether backups may run at all. Off until an owner turns it on with
 * {@code /keeper backup on confirm}.
 *
 * <p>Deliberately not a line in keeper.json: a config file gets copied between servers, pasted
 * from a guide or edited by whoever has file access, and none of those should be enough to
 * start writing zips of the world to disk. The switch lives in the world folder, so it belongs
 * to this world only, and the only thing that writes it is the command.
 */
public final class BackupSwitch {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE = "keeper-backups.json";

    /** What is on disk. Kept small and readable so an owner can see who turned it on. */
    private static final class State {
        boolean on;
        String by = "";
        String at = "";
    }

    private static State cached;
    private static Path cachedFor;

    private BackupSwitch() {}

    private static Path file(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve(FILE).normalize();
    }

    private static synchronized State state(MinecraftServer server) {
        Path f = file(server);
        if (cached != null && f.equals(cachedFor)) return cached;
        State s = new State();
        try {
            if (Files.exists(f)) {
                State read = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), State.class);
                if (read != null) s = read;
            }
        } catch (Exception e) {
            // A switch that cannot be read is a switch that is off.
            KeeperServer.LOG.warn("could not read {}, treating backups as off", f, e);
        }
        cached = s;
        cachedFor = f;
        return s;
    }

    public static boolean on(MinecraftServer server) {
        return state(server).on;
    }

    /** Who turned it on and when, or an empty string. */
    public static String who(MinecraftServer server) {
        State s = state(server);
        return s.on ? s.by + " on " + s.at : "";
    }

    /** Null on success, otherwise why it could not be written. */
    public static synchronized String set(MinecraftServer server, boolean on, String by) {
        State s = new State();
        s.on = on;
        s.by = by;
        s.at = Instant.now().toString();
        Path f = file(server);
        try {
            Files.writeString(f, GSON.toJson(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "could not write " + f + ": " + e.getMessage();
        }
        cached = s;
        cachedFor = f;
        KeeperServer.LOG.info("backups switched {} by {}", on ? "on" : "off", by);
        return null;
    }
}
