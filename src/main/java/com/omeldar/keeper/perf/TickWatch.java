package com.omeldar.keeper.perf;

import com.omeldar.keeper.Config;
import com.omeldar.keeper.KeeperServer;
import net.minecraft.server.MinecraftServer;

/**
 * How the server is holding up, in three time windows.
 *
 * <p>A server is either keeping up or it is not, and the honest unit is milliseconds per tick
 * rather than ticks per second. A tick has 50 ms to finish; TPS is pinned at 20 right up to
 * the moment it falls off a cliff, so it hides the whole interesting range between "fine" and
 * "about to be a problem". MSPT shows that range, which is where a redstone farm that is
 * slowly getting worse lives.
 *
 * <p>The measurement itself has to be free, or it becomes the thing it is measuring. This
 * keeps three running sums and three counters, and does one division when asked.
 */
public final class TickWatch {
    private static final int SECOND = 20;

    private final Window fiveSeconds = new Window(5 * SECOND);
    private final Window oneMinute = new Window(60 * SECOND);
    private final Window fiveMinutes = new Window(5 * 60 * SECOND);

    private long lastWarnAt;
    private long worstMsptMicros;
    private long worstAtTick;

    public void onTick(MinecraftServer server) {
        // The server already measures each tick; there is no reason to time it again.
        long[] times = server.getTickTimes();
        if (times == null || times.length == 0) return;
        long nanos = times[server.getTicks() % times.length];
        if (nanos <= 0) return;

        long micros = nanos / 1_000;
        fiveSeconds.add(micros);
        oneMinute.add(micros);
        fiveMinutes.add(micros);

        if (micros > worstMsptMicros) {
            worstMsptMicros = micros;
            worstAtTick = server.getTicks();
        }

        Config cfg = Config.get();
        if (cfg.warnMspt > 0 && oneMinute.count() >= 20 * SECOND) {
            double avg = oneMinute.averageMillis();
            long now = System.currentTimeMillis();
            if (avg >= cfg.warnMspt
                    && now - lastWarnAt > Math.max(30, cfg.warnEverySeconds) * 1000L) {
                lastWarnAt = now;
                KeeperServer.LOG.warn(String.format(
                        "server is behind: %.1f ms/tick over the last minute (budget 50). "
                      + "Run /keeper lag to see where it is going.", avg));
            }
        }
    }

    public double mspt5s() {
        return fiveSeconds.averageMillis();
    }

    public double mspt1m() {
        return oneMinute.averageMillis();
    }

    public double mspt5m() {
        return fiveMinutes.averageMillis();
    }

    public double worstMspt() {
        return worstMsptMicros / 1000.0;
    }

    public long worstAtTick() {
        return worstAtTick;
    }

    public void resetWorst() {
        worstMsptMicros = 0;
        worstAtTick = 0;
    }

    /** Effective ticks per second, which is what the number is usually asked for. */
    public static double tps(double mspt) {
        if (mspt <= 50) return 20.0;
        return Math.max(0, 1000.0 / mspt);
    }

    /** A fixed-size ring, summed as it goes so the average is a division. */
    private static final class Window {
        private final long[] samples;
        private int at;
        private int filled;
        private long sum;

        Window(int size) {
            this.samples = new long[size];
        }

        void add(long micros) {
            sum -= samples[at];
            samples[at] = micros;
            sum += micros;
            at = (at + 1) % samples.length;
            if (filled < samples.length) filled++;
        }

        int count() {
            return filled;
        }

        double averageMillis() {
            if (filled == 0) return 0;
            return (sum / (double) filled) / 1000.0;
        }
    }
}
