package com.donutsmp.plugin;

import org.bukkit.*;
import org.bukkit.block.Block;

import java.util.*;

/**
 * Generates amethyst geodes near activity zones.
 * The idea: where players have been active, geodes cluster —
 * suggesting someone has been mining/living in the area.
 */
public class GeodeGenerator {

    private final DonutSMPPlugin plugin;
    private final ActivityTracker activityTracker;
    private final Random random = new Random();
    private int generatedCount = 0;

    // Geode layers from outside in:
    // Smooth basalt shell → Calcite layer → Amethyst cluster core
    private static final int GEODE_RADIUS_OUTER = 7;
    private static final int GEODE_RADIUS_MIDDLE = 5;
    private static final int GEODE_RADIUS_INNER = 4;

    public GeodeGenerator(DonutSMPPlugin plugin, ActivityTracker activityTracker) {
        this.plugin = plugin;
        this.activityTracker = activityTracker;
    }

    public int generateGeodes() {
        int totalCount = plugin.getConfig().getInt("geode-count", 15);
        int perZone = plugin.getConfig().getInt("geodes-per-activity-zone", 3);
        List<Location> zones = activityTracker.getActivityZones();

        if (zones.isEmpty()) {
            plugin.getLogger().warning("No activity zones found! Generating random geodes.");
            generateRandomGeodes(totalCount);
            return generatedCount;
        }

        // Spread geodes across activity zones
        int generated = 0;
        for (Location zone : zones) {
            if (generated >= totalCount) break;
            int count = Math.min(perZone, totalCount - generated);
            generateGeodesNear(zone, count);
            generated += count;
        }

        return generatedCount;
    }

    public void generateGeodesNear(Location center, int count) {
        World w = center.getWorld();
        int radius = plugin.getConfig().getInt("activity-radius", 500);

        for (int i = 0; i < count; i++) {
            // Random offset within activity radius
            int dx = random.nextInt(radius * 2) - radius;
            int dz = random.nextInt(radius * 2) - radius;
            int x = center.getBlockX() + dx;
            int z = center.getBlockZ() + dz;

            // Geodes naturally form between Y -58 and Y 30
            int y = -50 + random.nextInt(60);

            Location geodeLoc = new Location(w, x, y, z);
            w.loadChunk(x >> 4, z >> 4);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                buildGeode(geodeLoc);
                generatedCount++;
            });
        }
    }

    private void generateRandomGeodes(int count) {
        World w = plugin.getServer().getWorlds().get(0);
        int border = plugin.getConfig().getInt("world-border", 10000) / 2;

        for (int i = 0; i < count; i++) {
            int x = random.nextInt(border * 2) - border;
            int z = random.nextInt(border * 2) - border;
            int y = -50 + random.nextInt(60);
            Location loc = new Location(w, x, y, z);
            w.loadChunk(x >> 4, z >> 4);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                buildGeode(loc);
                generatedCount++;
            });
        }
    }

    /**
     * Builds a proper amethyst geode with 3 layers:
     * Smooth basalt (outer) → Calcite (middle) → Amethyst blocks + budding amethyst (inner)
     * With amethyst clusters growing on inner walls.
     */
    private void buildGeode(Location center) {
        World w = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        // Build layers outside-in so inner layers overwrite outer
        buildSphere(w, cx, cy, cz, GEODE_RADIUS_OUTER, Material.SMOOTH_BASALT);
        buildSphere(w, cx, cy, cz, GEODE_RADIUS_MIDDLE, Material.CALCITE);
        buildSphere(w, cx, cy, cz, GEODE_RADIUS_INNER, Material.AMETHYST_BLOCK);

        // Hollow out the core
        buildSphere(w, cx, cy, cz, GEODE_RADIUS_INNER - 1, Material.AIR);

        // Replace some amethyst blocks with budding amethyst
        for (int x = cx - GEODE_RADIUS_INNER; x <= cx + GEODE_RADIUS_INNER; x++) {
            for (int y = cy - GEODE_RADIUS_INNER; y <= cy + GEODE_RADIUS_INNER; y++) {
                for (int z = cz - GEODE_RADIUS_INNER; z <= cz + GEODE_RADIUS_INNER; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() == Material.AMETHYST_BLOCK && random.nextFloat() < 0.3f) {
                        b.setType(Material.BUDDING_AMETHYST, false);
                    }
                }
            }
        }

        // Place amethyst clusters on inner walls (pointing inward)
        placeAmethystClusters(w, cx, cy, cz);
    }

    private void buildSphere(World w, int cx, int cy, int cz, int radius, Material mat) {
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = cy - radius; y <= cy + radius; y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    double dist = Math.sqrt(
                        Math.pow(x - cx, 2) +
                        Math.pow(y - cy, 2) +
                        Math.pow(z - cz, 2)
                    );
                    if (dist <= radius) {
                        w.getBlockAt(x, y, z).setType(mat, false);
                    }
                }
            }
        }
    }

    private void placeAmethystClusters(World w, int cx, int cy, int cz) {
        int innerR = GEODE_RADIUS_INNER - 1;
        // Place clusters randomly on the inner surface
        for (int x = cx - innerR; x <= cx + innerR; x++) {
            for (int y = cy - innerR; y <= cy + innerR; y++) {
                for (int z = cz - innerR; z <= cz + innerR; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() != Material.AIR) continue;

                    // Check if adjacent to amethyst wall
                    if (hasAmethystNeighbor(w, x, y, z) && random.nextFloat() < 0.15f) {
                        // Pick cluster stage randomly
                        Material cluster = pickClusterStage(random);
                        b.setType(cluster, false);
                    }
                }
            }
        }
    }

    private boolean hasAmethystNeighbor(World w, int x, int y, int z) {
        int[][] offsets = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] o : offsets) {
            Material m = w.getBlockAt(x+o[0], y+o[1], z+o[2]).getType();
            if (m == Material.AMETHYST_BLOCK || m == Material.BUDDING_AMETHYST) return true;
        }
        return false;
    }

    private Material pickClusterStage(Random r) {
        float f = r.nextFloat();
        if (f < 0.25f) return Material.SMALL_AMETHYST_BUD;
        if (f < 0.5f)  return Material.MEDIUM_AMETHYST_BUD;
        if (f < 0.75f) return Material.LARGE_AMETHYST_BUD;
        return Material.AMETHYST_CLUSTER;
    }

    public int getGeneratedCount() { return generatedCount; }
}
