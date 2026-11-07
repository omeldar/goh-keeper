package com.omeldar.keeper.backup;

import com.omeldar.keeper.Config;
import com.omeldar.keeper.KeeperServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A cold backup of the world, on a timer, without stopping the server.
 *
 * <p>The sequence matters, and getting it wrong is how backups turn out to be unrestorable
 * six months later:
 *
 * <ol>
 *   <li>Flush everything to disk on the main thread, and wait for it.</li>
 *   <li>Set every world to stop saving, so nothing is written while the copy runs.</li>
 *   <li>Zip the save directory on a worker thread, so the server keeps ticking.</li>
 *   <li>Turn saving back on, whatever happened - including if the zip threw.</li>
 * </ol>
 *
 * <p>Step two is the one most hot-copy backups skip, and it is why their region files
 * sometimes come back torn: a chunk flushed halfway through being read copies as half of two
 * different versions of itself. With saving off there is nothing in flight.
 *
 * <p>During a backup the world is in memory and not being written, so a crash mid-backup
 * loses whatever happened since step one. That is a few minutes of play against a backup that
 * actually restores, and it is the right trade - but it is a trade, so it is written down.
 */
public final class Backups {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private long lastBackupAtTick;
    private boolean anyoneSince;

    private Backups() {}

    private static final Backups INSTANCE = new Backups();

    public static Backups get() {
        return INSTANCE;
    }

    public void noteActivity() {
        anyoneSince = true;
    }

    public boolean busy() {
        return running.get();
    }

    public void onTick(MinecraftServer server) {
        Config cfg = Config.get();
        if (cfg.backupEveryMinutes <= 0 || !BackupSwitch.on(server)) return;
        if (server.getTicks() % 200 != 0) return;

        long dueTicks = cfg.backupEveryMinutes * 60L * 20L;
        if (server.getTicks() - lastBackupAtTick < dueTicks) return;

        if (cfg.skipBackupIfIdle && !anyoneSince && server.getPlayerManager().getCurrentPlayerCount() == 0) {
            // Nothing has happened; there is nothing new to keep. Reset the clock so this is
            // not re-checked every ten seconds.
            lastBackupAtTick = server.getTicks();
            return;
        }

        String refusal = start(server, "scheduled");
        if (refusal != null) {
            KeeperServer.LOG.info("skipping the scheduled backup: {}", refusal);
            // Try again in a few minutes rather than waiting out the whole interval.
            lastBackupAtTick = server.getTicks() - dueTicks + 5 * 60 * 20;
        }
    }

    /** Null when the backup started, otherwise why it did not. Call on the main thread. */
    public String start(MinecraftServer server, String why) {
        Config cfg = Config.get();
        if (!BackupSwitch.on(server)) {
            return "backups are switched off. An owner can turn them on with /keeper backup on";
        }
        if (!running.compareAndSet(false, true)) return "a backup is already running";

        double mspt = KeeperServer.ticks().mspt1m();
        if (cfg.backupMaxMspt > 0 && mspt > cfg.backupMaxMspt) {
            running.set(false);
            return String.format("the server is at %.1f ms/tick, over the %.1f limit", mspt,
                    cfg.backupMaxMspt);
        }

        Path source = server.getSavePath(WorldSavePath.ROOT).normalize();
        Path dir = Path.of(cfg.backupDir);
        if (!dir.isAbsolute()) dir = server.getRunDirectory().resolve(cfg.backupDir);
        final Path destDir = dir.normalize();

        // Refuse to write backups inside the world being backed up, which otherwise grows a
        // copy of a copy of a copy until the disk fills.
        if (destDir.startsWith(source)) {
            running.set(false);
            return "backupDir is inside the world directory - point it somewhere else";
        }

        final Path target = destDir.resolve(
                source.getFileName() + "_" + LocalDateTime.now().format(STAMP) + ".zip");

        server.saveAll(true, true, true);
        List<ServerWorld> frozen = new ArrayList<>();
        for (ServerWorld world : server.getWorlds()) {
            world.savingDisabled = true;
            frozen.add(world);
        }

        lastBackupAtTick = server.getTicks();
        anyoneSince = false;
        announce(server, "backup started (" + why + ")");
        KeeperServer.LOG.info("backup started: {} -> {}", source, target);

        Thread worker = new Thread(() -> {
            long started = System.currentTimeMillis();
            String outcome;
            long bytes = 0;
            try {
                Files.createDirectories(destDir);
                bytes = zip(source, target);
                prune(destDir, cfg.backupKeep);
                outcome = String.format("backup done in %ds, %.1f MB",
                        (System.currentTimeMillis() - started) / 1000,
                        bytes / 1024.0 / 1024.0);
                KeeperServer.LOG.info("{} -> {}", outcome, target);
            } catch (Exception e) {
                outcome = "backup FAILED: " + e;
                KeeperServer.LOG.error("backup failed", e);
                try {
                    Files.deleteIfExists(target);
                } catch (Exception ignored) {
                    // A half-written zip that cannot be deleted is still worth reporting the
                    // original failure for; nothing is gained by shadowing it.
                }
            } finally {
                // Back on the main thread, and unconditionally: a world left with saving off
                // because a zip threw would quietly lose everything on the next restart.
                server.execute(() -> {
                    for (ServerWorld world : frozen) world.savingDisabled = false;
                    running.set(false);
                });
            }
            final String message = outcome;
            server.execute(() -> announce(server, message));
        }, "keeper-backup");
        worker.setDaemon(false);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
        return null;
    }

    private static long zip(Path source, Path target) throws IOException {
        long[] total = {0};
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setLevel(4); // Fast enough not to matter, small enough to be worth it.
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = source.relativize(file).toString().replace('\\', '/');
                    // The lock file is held by the running server and is meaningless in a
                    // copy; taking it would fail the walk for nothing.
                    if (name.equals("session.lock")) return FileVisitResult.CONTINUE;
                    try {
                        zip.putNextEntry(new ZipEntry(name));
                        total[0] += Files.copy(file, zip);
                        zip.closeEntry();
                    } catch (IOException e) {
                        KeeperServer.LOG.warn("skipped {} in the backup: {}", name, e.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    KeeperServer.LOG.warn("could not read {} for the backup", file, e);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return Files.size(target);
    }

    private static void prune(Path dir, int keep) throws IOException {
        if (keep <= 0) return;
        List<Path> zips;
        try (var stream = Files.list(dir)) {
            zips = stream.filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing(Backups::modified).reversed())
                    .toList();
        }
        for (int i = keep; i < zips.size(); i++) {
            Files.deleteIfExists(zips.get(i));
            KeeperServer.LOG.info("pruned old backup {}", zips.get(i).getFileName());
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    public List<String> list(MinecraftServer server) {
        Config cfg = Config.get();
        Path dir = Path.of(cfg.backupDir);
        if (!dir.isAbsolute()) dir = server.getRunDirectory().resolve(cfg.backupDir);
        List<String> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing(Backups::modified).reversed())
                    .forEach(p -> {
                        try {
                            out.add(String.format("%s  %.1f MB", p.getFileName(),
                                    Files.size(p) / 1024.0 / 1024.0));
                        } catch (IOException ignored) {
                            out.add(p.getFileName().toString());
                        }
                    });
        } catch (Exception e) {
            out.add("no backups found in " + dir);
        }
        return out;
    }

    private static void announce(MinecraftServer server, String message) {
        server.getPlayerManager().broadcast(
                Text.literal("[keeper] " + message).formatted(Formatting.DARK_GRAY), false);
    }
}
