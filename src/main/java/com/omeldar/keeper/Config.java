package com.omeldar.keeper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings, at {@code config/keeper.json}. Everything here is off or conservative by default,
 * because a server mod that surprises its admin is worse than no server mod.
 */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance;

    // ------------------------------------------------------------- performance

    /**
     * Warn in the server log when the rolling average tick time crosses this, in
     * milliseconds. 50 is the budget for a tick; past that the server is behind.
     */
    public double warnMspt = 45.0;
    /** How long to go between warnings, so a bad evening does not fill the log. */
    public int warnEverySeconds = 300;

    // --------------------------------------------------------------------- afk

    /** Announce when a player goes idle and when they come back. */
    public boolean announceAfk = true;
    /** Idle for this long and they are afk. */
    public int afkMinutes = 8;

    // ----------------------------------------------------------------- quality
    //
    // Small things a player would otherwise write down by hand. Nothing here changes what
    // the game does - only what it tells you about what it already did.

    /** Tell a player where they died, privately, with the coordinates. */
    public boolean deathCoordinates = true;
    /** Say how many are sleeping, and how many it takes, when someone gets into bed. */
    public boolean sleepProgress = true;
    /** Greet a player with their total playtime when they join. */
    public boolean joinPlaytime = true;

    // ----------------------------------------------------------------- backups

    // Whether backups run at all is not set here. It is a switch only /keeper backup on
    // confirm can flip; see BackupSwitch. A backupsEnabled line left in an old file is ignored.
    /** Where to write them. Relative paths are relative to the server directory. */
    public String backupDir = "backups";
    /** Minutes between automatic backups. */
    public int backupEveryMinutes = 180;
    /** Keep this many, deleting the oldest. */
    public int backupKeep = 8;
    /** Skip the automatic backup when nobody has been on since the last one. */
    public boolean skipBackupIfIdle = true;
    /**
     * Refuse to start a backup while the server is already behind, in ms of average tick
     * time. A backup that makes a bad evening worse is not a kindness.
     */
    public double backupMaxMspt = 40.0;

    // ---------------------------------------------------------------- plumbing

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("keeper.json");
    }

    public static synchronized Config get() {
        if (instance == null) load();
        return instance;
    }

    public static synchronized void load() {
        Config cfg = null;
        try {
            if (Files.exists(file())) {
                cfg = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), Config.class);
            }
        } catch (Exception e) {
            KeeperServer.LOG.warn("keeper.json unreadable, falling back to defaults", e);
        }
        instance = cfg == null ? new Config() : cfg;
        save();
    }

    public static synchronized void save() {
        if (instance == null) return;
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(instance), StandardCharsets.UTF_8);
        } catch (Exception e) {
            KeeperServer.LOG.warn("could not write keeper.json", e);
        }
    }
}
