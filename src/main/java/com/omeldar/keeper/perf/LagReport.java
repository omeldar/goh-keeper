package com.omeldar.keeper.perf;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the time is going, on demand.
 *
 * <p>The server rules say to keep big farms away from spawn for performance. This is what
 * makes that rule checkable instead of a matter of opinion: it counts entities per chunk and
 * per type across every dimension, and names the worst offenders with coordinates you can fly
 * to. Most server lag that is anybody's fault is a pile of entities in one chunk - item
 * entities a hopper cannot keep up with, a mob farm with no killing floor, a few hundred
 * minecarts somebody forgot.
 *
 * <p>It only runs when asked. A continuous census would be a sampling profiler that costs
 * more than the thing it watches, and on a server that is already behind, that is the last
 * thing to add.
 */
public final class LagReport {
    public final List<String> lines = new ArrayList<>();

    public static final class ChunkCount {
        public final ServerWorld world;
        public final ChunkPos pos;
        public final int entities;

        ChunkCount(ServerWorld world, ChunkPos pos, int entities) {
            this.world = world;
            this.pos = pos;
            this.entities = entities;
        }
    }

    public static LagReport take(MinecraftServer server, int topN) {
        LagReport report = new LagReport();

        int grandTotal = 0;
        Map<EntityType<?>, Integer> byType = new HashMap<>();
        List<ChunkCount> hotChunks = new ArrayList<>();

        for (ServerWorld world : server.getWorlds()) {
            Map<Long, Integer> perChunk = new HashMap<>();
            int worldTotal = 0;

            for (Entity entity : world.iterateEntities()) {
                worldTotal++;
                byType.merge(entity.getType(), 1, Integer::sum);
                ChunkPos chunk = entity.getChunkPos();
                perChunk.merge(chunk.toLong(), 1, Integer::sum);
            }
            grandTotal += worldTotal;

            for (Map.Entry<Long, Integer> e : perChunk.entrySet()) {
                // Below about thirty in one chunk there is nothing to report; a village or a
                // flock of chickens is not a problem and should not be listed as one.
                if (e.getValue() >= 30) {
                    hotChunks.add(new ChunkCount(world, new ChunkPos(e.getKey()), e.getValue()));
                }
            }

            report.lines.add(String.format("%s: %d entities, %d chunks loaded",
                    shortName(world), worldTotal, world.getChunkManager().getLoadedChunkCount()));
        }

        report.lines.add("total entities: " + grandTotal);

        hotChunks.sort(Comparator.comparingInt((ChunkCount c) -> c.entities).reversed());
        if (hotChunks.isEmpty()) {
            report.lines.add("no chunk has 30 or more entities in it - nothing stands out");
        } else {
            report.lines.add("busiest chunks:");
            for (ChunkCount c : hotChunks.subList(0, Math.min(topN, hotChunks.size()))) {
                report.lines.add(String.format("  %d entities at %d %d in %s  (block %d ~ %d)",
                        c.entities, c.pos.x, c.pos.z, shortName(c.world),
                        c.pos.getStartX() + 8, c.pos.getStartZ() + 8));
            }
        }

        List<Map.Entry<EntityType<?>, Integer>> types = new ArrayList<>(byType.entrySet());
        types.sort(Map.Entry.<EntityType<?>, Integer>comparingByValue().reversed());
        if (!types.isEmpty()) {
            report.lines.add("most common entities:");
            for (Map.Entry<EntityType<?>, Integer> e : types.subList(0, Math.min(topN, types.size()))) {
                report.lines.add(String.format("  %d x %s",
                        e.getValue(), Registries.ENTITY_TYPE.getId(e.getKey())));
            }
        }

        return report;
    }

    private static String shortName(ServerWorld world) {
        String id = world.getRegistryKey().getValue().toString();
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
