package com.donutsmp.plugin;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.Material;

import java.util.*;

/**
 * Handles placement strategy and concealment for each litematic schematic.
 *
 * Concealment scales with base footprint — bigger bases get:
 *   - Wider ore scatter radius (proportional to sqrt of footprint area)
 *   - More fake ore count
 *   - More decoy geodes
 *   - Deeper burial offset
 *
 * 50/50 ESP shimmer: after pasting, the outer shell of each base is randomly
 * replaced with either the base material (visible on ESP) or a "ghost block"
 * (deepslate/stone that blends in). This is re-randomised every paste, so
 * the same base looks different to different players scanning at different times.
 *
 * Hard (difficulty 3): extraHidden=true — full decoy stack + shimmer
 * Medium (difficulty 2): shimmer only, lighter decoys
 * Easy (difficulty 1): minimal concealment, no shimmer
 */
public class SchematicPlacer {

    private final DonutSMPPlugin plugin;
    private final LitematicLoader loader;
    private final ActivityTracker activityTracker;
    private final Random random = new Random();
    private int placedCount = 0;

    private static final int[][] DIFFICULTY_CONFIG = {
        // {minDist, maxDist, minDepth, maxDepth}
        {500,  2000,  -20, -10},  // difficulty 1 easy
        {2000, 4000,  -40, -25},  // difficulty 2 medium
        {5000, 9000,  -60, -50},  // difficulty 3 hard
    };

    /**
     * Extra depth offset applied on top of DIFFICULTY_CONFIG based on footprint.
     * Bigger bases sink deeper so their roof is further from the surface.
     * Scale buckets (sizeX * sizeZ area):
     *   < 600  (tiny)   → 0  extra
     *   < 1500 (small)  → -5
     *   < 3000 (medium) → -10
     *   < 5000 (large)  → -20
     *   >= 5000 (huge)  → -35  (purplex 84×67 = 5628, drdonut 22×65 = 1430)
     */
    private int extraDepthForSize(LitematicLoader.SchematicMeta meta) {
        int area = meta.sizeX * meta.sizeZ;
        if (area >= 5000) return -35;
        if (area >= 3000) return -20;
        if (area >= 1500) return -10;
        if (area >= 600)  return -5;
        return 0;
    }

    public SchematicPlacer(DonutSMPPlugin plugin, LitematicLoader loader, ActivityTracker activityTracker) {
        this.plugin = plugin;
        this.loader = loader;
        this.activityTracker = activityTracker;
    }

    // ── Discoverability settings (config.yml -> base-finding.*) ───────────────
    // Defaults are the "easy to find" values so an older config.yml still works.
    private double distanceMultiplier() { return plugin.getConfig().getDouble("base-finding.distance-multiplier", 0.35); }
    private int    raiseBases()         { return plugin.getConfig().getInt("base-finding.raise-bases", 25); }
    private boolean sizeDepthPenalty()  { return plugin.getConfig().getBoolean("base-finding.size-depth-penalty", false); }
    private boolean decoys()            { return plugin.getConfig().getBoolean("base-finding.decoys", false); }
    private boolean espShimmer()        { return plugin.getConfig().getBoolean("base-finding.esp-shimmer", false); }
    private boolean stoneCap()          { return plugin.getConfig().getBoolean("base-finding.stone-cap", false); }

    /** Picks where a schematic goes, using the difficulty band scaled by the config. */
    private Location pickLocation(World world, String key, LitematicLoader.SchematicMeta meta) {
        // Exact spot from config (e.g. picked on a seed map): base-finding.fixed-locations.<key>: "x,y,z"
        String fixed = plugin.getConfig().getString("base-finding.fixed-locations." + key);
        if (fixed != null) {
            try {
                String[] p = fixed.replace(" ", "").split(",");
                Location fl = new Location(world, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                warnIfCrowded(world, key, fl, meta);
                return fl;
            } catch (Exception e) {
                plugin.getLogger().warning("Bad fixed-locations entry for " + key + " (use \"x,y,z\"): " + fixed);
            }
        }

        int[] config = DIFFICULTY_CONFIG[meta.difficulty - 1];

        double mult = Math.max(0.05, distanceMultiplier());
        int minDist = Math.max(150, (int) (config[0] * mult));
        int maxDist = Math.max(minDist + 50, (int) (config[1] * mult));

        int extra = sizeDepthPenalty() ? extraDepthForSize(meta) : 0;
        int minY = config[2] + extra + raiseBases();
        int maxY = config[3] + extra + raiseBases();

        // Never ask for a Y below the bottom of the world (that used to make big hard bases fail to place)
        int floorY = world.getMinHeight() + 6;
        if (minY < floorY) minY = floorY;
        if (maxY <= minY) maxY = minY + 1;

        return findPlacementLocation(world, minDist, maxDist, minY, maxY,
                Math.max(1, meta.sizeX), Math.max(1, meta.sizeZ));
    }

    /** A pinned (fixed-locations) spot is always honoured, but tell the admin if it sits right next to something. */
    private void warnIfCrowded(World world, String key, Location at, LitematicLoader.SchematicMeta meta) {
        PlacementRegistry reg = plugin.getPlacementRegistry();
        if (reg == null) return;
        int gap = reg.configuredGap();
        if (gap <= 0) return;
        PlacementRegistry.Nearby n = reg.findNearest(world, at.getBlockX(), at.getBlockZ(),
                at.getBlockX() + Math.max(1, meta.sizeX) - 1, at.getBlockZ() + Math.max(1, meta.sizeZ) - 1);
        if (n != null && n.gap < gap) {
            plugin.getLogger().warning("Fixed location for '" + key + "' is only " + n.gap + " blocks from "
                    + n.entry.describe() + " (spacing is " + gap + "). Placing it anyway because you pinned it.");
        }
    }

    public int placeAllSchematics() {
        World world = plugin.getServer().getWorlds().get(0);
        placedCount = 0;

        for (Map.Entry<String, LitematicLoader.SchematicMeta> entry : LitematicLoader.SCHEMATICS.entrySet()) {
            String key = entry.getKey();
            LitematicLoader.SchematicMeta meta = entry.getValue();
            if (placeOne(world, key, meta)) placedCount++;
        }
        return placedCount;
    }

    /** Places every schematic on the main thread, one every 2 seconds, then calls done(placedCount). */
    public void placeAllSchematicsSliced(java.util.function.IntConsumer done) {
        World world = plugin.getServer().getWorlds().get(0);
        placedCount = 0;
        List<Map.Entry<String, LitematicLoader.SchematicMeta>> list = new ArrayList<>(LitematicLoader.SCHEMATICS.entrySet());
        placeNext(world, list, 0, done);
    }

    private void placeNext(World world, List<Map.Entry<String, LitematicLoader.SchematicMeta>> list,
                           int index, java.util.function.IntConsumer done) {
        if (index >= list.size()) { done.accept(placedCount); return; }
        Map.Entry<String, LitematicLoader.SchematicMeta> e = list.get(index);
        try {
            if (placeOne(world, e.getKey(), e.getValue())) placedCount++;
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed placing " + e.getKey() + ": " + ex);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> placeNext(world, list, index + 1, done), 40L);
    }

    /**
     * Places schematics in a ring around a center point (the player), spaced by size so they don't overlap.
     * keys = which schematics to place; one every 2 seconds on the main thread. Each sits on the surface.
     */
    public void placeAround(Location center, int minRadius, List<String> keys, java.util.function.IntConsumer done) {
        World world = center.getWorld();
        int gap = 12;
        double totalArc = 0, maxDim = 0;
        for (String k : keys) {
            LitematicLoader.SchematicMeta m = LitematicLoader.SCHEMATICS.get(k);
            int d = Math.max(m.sizeX, m.sizeZ);
            totalArc += d + gap;
            maxDim = Math.max(maxDim, d);
        }
        // Ring big enough that the total arc length fits and nothing overlaps the player.
        double radius = Math.max(minRadius, Math.max(totalArc / (2 * Math.PI), maxDim / 2.0 + 20));
        List<Location> origins = new ArrayList<>();
        double angle = 0;
        for (String k : keys) {
            LitematicLoader.SchematicMeta m = LitematicLoader.SCHEMATICS.get(k);
            int d = Math.max(m.sizeX, m.sizeZ);
            double span = (d + gap) / radius;           // angle this schematic occupies
            double mid = angle + span / 2.0;
            int cx = center.getBlockX() + (int) Math.round(Math.cos(mid) * radius);
            int cz = center.getBlockZ() + (int) Math.round(Math.sin(mid) * radius);
            int ox = cx - m.sizeX / 2;
            int oz = cz - m.sizeZ / 2;
            world.getChunkAt(cx >> 4, cz >> 4).load(true);
            int y = world.getHighestBlockYAt(cx, cz, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
            origins.add(new Location(world, ox, y, oz));
            angle += span;
        }
        placedCount = 0;
        placeAroundNext(keys, origins, 0, done);
    }

    private void placeAroundNext(List<String> keys, List<Location> origins, int i, java.util.function.IntConsumer done) {
        if (i >= keys.size()) { done.accept(placedCount); return; }
        String k = keys.get(i);
        Location loc = origins.get(i);
        try {
            if (loader.pasteLitematic(k, loc, PlacementRegistry.MANUAL)) {
                placedCount++;
                plugin.getLogger().info("Placed " + k + " around player at " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed placing " + k + ": " + ex);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> placeAroundNext(keys, origins, i + 1, done), 40L);
    }

    public boolean placeSchematic(String key) {
        LitematicLoader.SchematicMeta meta = LitematicLoader.SCHEMATICS.get(key);
        if (meta == null) return false;
        World world = plugin.getServer().getWorlds().get(0);
        boolean ok = placeOne(world, key, meta);
        if (ok) placedCount++;
        return ok;
    }

    private boolean placeOne(World world, String key, LitematicLoader.SchematicMeta meta) {
        Location loc = pickLocation(world, key, meta);
        if (loc == null) {
            plugin.getLogger().warning("Could not find placement for: " + key);
            return false;
        }

        plugin.getLogger().info("Placing " + meta.displayName + " at " +
            loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() +
            " (difficulty " + meta.difficulty + ", footprint " + meta.sizeX + "x" + meta.sizeZ + ")");

        world.loadChunk(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);

        if (meta.extraHidden && decoys()) {
            addHardConcealmentLayer(world, loc, meta);
        }

        boolean pinned = plugin.getConfig().getString("base-finding.fixed-locations." + key) != null;
        boolean success = loader.pasteLitematic(key, loc, pinned ? PlacementRegistry.MANUAL : null);
        if (success) {
            conceaAfterPaste(world, loc, meta);
            if (meta.difficulty >= 2 && espShimmer()) {
                applyEspShimmer(world, loc, meta);
            }
            activityTracker.addActivityZone(new Location(world, loc.getBlockX(), 64, loc.getBlockZ()));
        }
        return success;
    }

    // =========================================================================
    // ESP SHIMMER — 50/50 chance per outer shell block
    // =========================================================================

    /**
     * Walks the outer shell (1-block skin) of the pasted schematic bounding box.
     * Each shell block is independently coin-flipped:
     *   HEADS (50%) — left as-is (visible on ESP as a foreign block)
     *   TAILS (50%) — replaced with a "ghost block" that matches surrounding
     *                  terrain type at that Y level, making ESP show nothing there
     *
     * Result: the base outline appears fragmented and inconsistent. Some scans
     * show a full outline, some show half, some show almost nothing. Players
     * can't tell if what they're seeing is a real base or just natural terrain.
     *
     * Ghost block selection scales with depth so it always matches surroundings:
     *   Y < -8  → DEEPSLATE (deepslate zone)
     *   Y ≥ -8  → STONE
     * Extra noise blocks (TUFF, CALCITE, GRAVEL) are scattered at 10% rate
     * to break up any remaining pattern recognition.
     */
    private void applyEspShimmer(World world, Location origin, LitematicLoader.SchematicMeta meta) {
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();

        int sx = meta.sizeX;
        int sy = meta.sizeY;
        int sz = meta.sizeZ;

        // Determine ghost block based on depth
        boolean deepslateZone = oy < -8;
        Material[] ghostBlocks = deepslateZone
            ? new Material[]{Material.DEEPSLATE, Material.DEEPSLATE, Material.DEEPSLATE, Material.TUFF, Material.DEEPSLATE}
            : new Material[]{Material.STONE, Material.STONE, Material.STONE, Material.GRAVEL, Material.CALCITE};

        int shimmerCount = 0;
        int totalShell = 0;

        for (int x = ox - 1; x <= ox + sx; x++) {
            for (int y = oy - 1; y <= oy + sy; y++) {
                for (int z = oz - 1; z <= oz + sz; z++) {
                    // Only process the outer shell (1 block outside the schematic)
                    boolean onShell = (x == ox - 1 || x == ox + sx ||
                                       y == oy - 1 || y == oy + sy ||
                                       z == oz - 1 || z == oz + sz);
                    if (!onShell) continue;

                    totalShell++;
                    Block block = world.getBlockAt(x, y, z);

                    // 50/50 coin flip per block
                    if (random.nextBoolean()) {
                        // Ghost — replace with terrain-matching block
                        Material ghost = ghostBlocks[random.nextInt(ghostBlocks.length)];
                        block.setType(ghost, false);
                        shimmerCount++;
                    }
                    // else: leave the block as-is (or air) — ESP will see it
                }
            }
        }

        plugin.getLogger().info("[Shimmer] " + meta.displayName +
            " — ghosted " + shimmerCount + "/" + totalShell + " shell blocks (~50%)");
    }

    // =========================================================================
    // CONCEALMENT — scales with footprint size
    // =========================================================================

    /**
     * Compute a scale factor from the base footprint.
     * A 20x20 base = factor 1.0 (baseline). A 84x67 base = factor ~5.3.
     * Used to multiply ore count, geode count, and scatter radius.
     */
    private float footprintScale(LitematicLoader.SchematicMeta meta) {
        double area = meta.sizeX * meta.sizeZ;
        double baseline = 20.0 * 20.0; // smallest typical base
        return (float) Math.sqrt(area / baseline);
    }

    private void addHardConcealmentLayer(World world, Location origin, LitematicLoader.SchematicMeta meta) {
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        float scale = footprintScale(meta);

        // === DECOY LAYER 1: fake abandoned base above ===
        int decoyY = oy + 20;
        // Scale room to base size — bigger bases get bigger decoys
        int roomW = Math.min(6 + (int)(meta.sizeX * 0.15f), 16);
        int roomD = Math.min(6 + (int)(meta.sizeZ * 0.15f), 16);
        carveRoom(world, ox - roomW/2, decoyY, oz - roomD/2, roomW, 4, roomD, Material.STONE_BRICKS);
        int crackedCount = (int)(15 * scale);
        for (int i = 0; i < crackedCount; i++) {
            int rx = ox - roomW/2 + 1 + random.nextInt(Math.max(1, roomW - 2));
            int ry = decoyY + random.nextInt(3);
            int rz = oz - roomD/2 + 1 + random.nextInt(Math.max(1, roomD - 2));
            world.getBlockAt(rx, ry, rz).setType(Material.CRACKED_STONE_BRICKS, false);
        }
        placeJunkChest(world, ox, decoyY + 1, oz);
        world.getBlockAt(ox + 1, decoyY + 1, oz).setType(Material.TORCH, false);

        // === DECOY LAYER 2: fake mine shaft ===
        for (int y = oy + 5; y < decoyY; y++) {
            world.getBlockAt(ox, y, oz - 2).setType(Material.AIR, false);
            world.getBlockAt(ox + 1, y, oz - 2).setType(Material.AIR, false);
        }
        carveRoom(world, ox - 1, oy + 3, oz - 4, 4, 3, 4, Material.AIR);
        placeJunkChest(world, ox, oy + 4, oz - 3);
        world.getBlockAt(ox, oy + 15, oz - 2).setType(Material.GRAVEL, false);
        world.getBlockAt(ox + 1, oy + 15, oz - 2).setType(Material.GRAVEL, false);

        // === FAKE ORE SCATTER — scaled to footprint ===
        int radius = (int)(30 * scale);     // bigger base = wider scatter
        int oreCount = (int)(80 * scale);   // bigger base = more fake ores
        // Cap so we don't go insane on the largest bases
        radius = Math.min(radius, 120);
        oreCount = Math.min(oreCount, 500);

        for (int i = 0; i < oreCount; i++) {
            int rx = ox - radius + random.nextInt(radius * 2);
            int ry = oy - 5 + random.nextInt(15);
            int rz = oz - radius + random.nextInt(radius * 2);
            Block b = world.getBlockAt(rx, ry, rz);
            if (b.getType() == Material.DEEPSLATE || b.getType() == Material.STONE) {
                b.setType(pickDecoyOre(), false);
            }
        }

        // === GEODE SHELLS — scaled to footprint ===
        int geodeCount = Math.max(3, (int)(3 * scale));
        geodeCount = Math.min(geodeCount, 12);
        int geodeRadius = (int)(60 * scale);
        geodeRadius = Math.min(geodeRadius, 200);

        for (int i = 0; i < geodeCount; i++) {
            int gx = ox + random.nextInt(geodeRadius * 2) - geodeRadius;
            int gz = oz + random.nextInt(geodeRadius * 2) - geodeRadius;
            int gy = oy + random.nextInt(10) - 5;
            buildMiniGeode(world, gx, gy, gz);
        }

        plugin.getLogger().info("[Conceal] " + meta.displayName +
            " scale=" + String.format("%.1f", scale) +
            " ores=" + oreCount + " radius=" + radius + " geodes=" + geodeCount);
    }

    private void conceaAfterPaste(World world, Location origin, LitematicLoader.SchematicMeta meta) {
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();

        if (meta.extraHidden) {
            if (stoneCap()) {
                int topY = oy + meta.sizeY + 1;
                for (int x = ox; x < ox + meta.sizeX; x++) {
                    for (int z = oz; z < oz + meta.sizeZ; z++) {
                        Block top = world.getBlockAt(x, topY, z);
                        if (top.getType() == Material.AIR) {
                            Material cover = (topY < -20) ? Material.DEEPSLATE : Material.STONE;
                            top.setType(cover, false);
                        }
                    }
                }
            }
            if (decoys() && meta.filename.contains("DrDonut")) {
                addNetherDisguise(world, origin, meta);
            }
        } else {
            for (int i = 0; i < 4; i++) {
                world.getBlockAt(ox, oy + meta.sizeY + i, oz).setType(Material.GRAVEL, false);
            }
        }
    }

    private void addNetherDisguise(World world, Location origin, LitematicLoader.SchematicMeta meta) {
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        for (int x = ox - 2; x < ox + meta.sizeX + 2; x++) {
            for (int y = oy - 1; y < oy + meta.sizeY + 2; y++) {
                for (int z = oz - 2; z < oz + meta.sizeZ + 2; z++) {
                    boolean isShell = (x < ox || x >= ox + meta.sizeX ||
                                       y < oy || y >= oy + meta.sizeY ||
                                       z < oz || z >= oz + meta.sizeZ);
                    if (!isShell) continue;
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() == Material.DEEPSLATE || b.getType() == Material.STONE ||
                        b.getType() == Material.AIR) {
                        b.setType(Material.NETHERRACK, false);
                    }
                }
            }
        }
        for (int i = 0; i < 12; i++) {
            int rx = ox - 1 + random.nextInt(meta.sizeX + 2);
            int ry = oy + meta.sizeY + random.nextInt(3);
            int rz = oz - 1 + random.nextInt(meta.sizeZ + 2);
            world.getBlockAt(rx, ry, rz).setType(Material.GLOWSTONE, false);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void carveRoom(World world, int x, int y, int z, int sx, int sy, int sz, Material wall) {
        for (int dx = 0; dx <= sx; dx++)
            for (int dy = 0; dy <= sy; dy++)
                for (int dz = 0; dz <= sz; dz++) {
                    boolean edge = dx==0||dx==sx||dy==0||dy==sy||dz==0||dz==sz;
                    if (edge && wall != Material.AIR)
                        world.getBlockAt(x+dx, y+dy, z+dz).setType(wall, false);
                    else if (!edge)
                        world.getBlockAt(x+dx, y+dy, z+dz).setType(Material.AIR, false);
                }
    }

    private void placeJunkChest(World world, int x, int y, int z) {
        world.getBlockAt(x, y, z).setType(Material.CHEST, false);
        Block b = world.getBlockAt(x, y, z);
        if (b.getState() instanceof org.bukkit.block.Chest) {
            org.bukkit.block.Chest chest = (org.bukkit.block.Chest) b.getState();
            chest.getInventory().setItem(0, new org.bukkit.inventory.ItemStack(Material.COBBLESTONE, 12));
            chest.getInventory().setItem(2, new org.bukkit.inventory.ItemStack(Material.OAK_PLANKS, 7));
            chest.getInventory().setItem(5, new org.bukkit.inventory.ItemStack(Material.IRON_PICKAXE, 1));
            chest.getInventory().setItem(8, new org.bukkit.inventory.ItemStack(Material.TORCH, 23));
            chest.update();
        }
    }

    private void buildMiniGeode(World world, int cx, int cy, int cz) {
        int r = 4 + random.nextInt(3);
        for (int x = cx-r; x <= cx+r; x++)
            for (int y = cy-r; y <= cy+r; y++)
                for (int z = cz-r; z <= cz+r; z++) {
                    double dist = Math.sqrt(Math.pow(x-cx,2)+Math.pow(y-cy,2)+Math.pow(z-cz,2));
                    if (dist <= r)   world.getBlockAt(x,y,z).setType(Material.SMOOTH_BASALT, false);
                    if (dist <= r-1) world.getBlockAt(x,y,z).setType(Material.CALCITE, false);
                    if (dist <= r-2) world.getBlockAt(x,y,z).setType(Material.AMETHYST_BLOCK, false);
                    if (dist <= r-3) world.getBlockAt(x,y,z).setType(Material.AIR, false);
                }
    }

    private Material pickDecoyOre() {
        float f = random.nextFloat();
        if (f < 0.40f) return Material.DEEPSLATE_IRON_ORE;
        if (f < 0.65f) return Material.DEEPSLATE_GOLD_ORE;
        if (f < 0.80f) return Material.DEEPSLATE_REDSTONE_ORE;
        if (f < 0.90f) return Material.DEEPSLATE_LAPIS_ORE;
        if (f < 0.96f) return Material.DEEPSLATE_DIAMOND_ORE;
        return Material.DEEPSLATE_EMERALD_ORE;
    }

    /**
     * Picks a random spot in the distance band that (a) is dry land and (b) keeps at least
     * base-spacing.min-gap blocks (edge-to-edge) between this schematic and every existing base, stash,
     * farm or schematic. If the band is too crowded for the full gap it retries with 2/3 then 1/3 of it
     * before giving up (returns null, and the caller logs "Could not find placement").
     */
    private Location findPlacementLocation(World world, int minDist, int maxDist, int minY, int maxY,
                                           int sizeX, int sizeZ) {
        int border = plugin.getConfig().getInt("world-border", 10000);
        boolean landOnly = plugin.getConfig().getBoolean("base-finding.land-only", true);

        PlacementRegistry reg = plugin.getPlacementRegistry();
        int fullGap = reg != null ? reg.configuredGap() : 0;
        int[] gaps = fullGap > 0 ? new int[]{fullGap, fullGap * 2 / 3, fullGap / 3} : new int[]{0};

        for (int gap : gaps) {
            for (int attempt = 0; attempt < 100; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2;
                int dist = minDist + random.nextInt(Math.max(1, maxDist - minDist));
                int x = (int)(Math.cos(angle) * dist);
                int z = (int)(Math.sin(angle) * dist);
                int y = minY + random.nextInt(Math.max(1, maxY - minY));
                if (Math.abs(x) > border || Math.abs(z) > border) continue;
                if (y < world.getMinHeight() + 5) continue;
                // Spacing first: it's cheap and saves loading a chunk for a spot we'd reject anyway
                if (gap > 0 && !reg.isClear(world, x, z, x + sizeX - 1, z + sizeZ - 1, gap)) continue;
                world.loadChunk(x >> 4, z >> 4);
                // Use the REAL generated terrain (from the world seed): skip oceans and rivers
                if (landOnly && !isLand(world, x, z)) continue;
                if (gap > 0 && gap < fullGap) {
                    plugin.getLogger().info("Area is crowded - placed with a reduced spacing of " + gap
                            + " blocks (wanted " + fullGap + ").");
                }
                return new Location(world, x, y, z);
            }
        }
        return null;
    }

    /** True if the surface above (x, z) is dry land, so players walking around can actually reach it. */
    private boolean isLand(World world, int x, int z) {
        String biome = String.valueOf(world.getBiome(x, 64, z)).toUpperCase();
        if (biome.contains("OCEAN") || biome.contains("RIVER")) return false;
        return world.getHighestBlockYAt(x, z) >= world.getSeaLevel();
    }

    public int getPlacedCount() { return placedCount; }
}
