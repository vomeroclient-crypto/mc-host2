package com.donutsmp.plugin;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;

import java.io.*;
import java.util.*;

/**
 * Remembers every base / stash / farm / schematic the plugin has placed
 * (saved to plugins/DonutSMPWorld/placements.txt).
 *
 * Two jobs:
 *   1. SPACING  - generators ask isClear()/findNearest() so new placements are not put right next to
 *                 an existing base, stash, farm or schematic (config: base-spacing.*).
 *   2. CLEANUP  - /removebase uses findCrowded() + removeAll() to delete placements that ended up too
 *                 close to an older one.
 *
 * Distances are measured on the XZ plane, edge-to-edge between the footprints (not centre-to-centre),
 * so a huge base and a tiny stash are judged fairly.
 */
public class PlacementRegistry {

    public static final String BASE      = "base";       // procedural base from /generatebases
    public static final String SCHEMATIC = "schematic";  // litematic placed by /placeschematics, /placeschematic
    public static final String FARM      = "farm";       // farm placed by /spawnfarm (never auto-removed)
    public static final String STASH     = "stash";      // /generatestash
    public static final String MANUAL    = "manual";     // admin chose the spot (ring, fixed-locations) (never auto-removed)

    /** Farms and hand-placed things are obstacles for spacing, but /removebase never deletes them. */
    public static boolean isProtected(String kind) {
        return FARM.equals(kind) || MANUAL.equals(kind);
    }

    // ── Data classes ─────────────────────────────────────────────────────────

    public static class Entry {
        public final long seq;                       // placement order (lower = older)
        public final String kind, key, worldName;
        public final int minX, minY, minZ, maxX, maxY, maxZ;   // inclusive corners

        Entry(long seq, String kind, String key, String worldName,
              int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.seq = seq; this.kind = kind; this.key = key; this.worldName = worldName;
            this.minX = Math.min(minX, maxX); this.maxX = Math.max(minX, maxX);
            this.minY = Math.min(minY, maxY); this.maxY = Math.max(minY, maxY);
            this.minZ = Math.min(minZ, maxZ); this.maxZ = Math.max(minZ, maxZ);
        }

        public int centerX() { return (minX + maxX) / 2; }
        public int centerY() { return (minY + maxY) / 2; }
        public int centerZ() { return (minZ + maxZ) / 2; }

        public String describe() {
            return kind + " '" + key + "' at " + centerX() + ", " + centerY() + ", " + centerZ();
        }
    }

    /** The closest existing placement to a proposed footprint. */
    public static class Nearby {
        public final Entry entry;
        public final int gap;
        Nearby(Entry entry, int gap) { this.entry = entry; this.gap = gap; }
    }

    /** One placement /removebase wants to delete, and the older one it is too close to. */
    public static class Removal {
        public final Entry entry;
        public final Entry because;
        public final int gap;
        Removal(Entry entry, Entry because, int gap) { this.entry = entry; this.because = because; this.gap = gap; }
    }

    // ── State ────────────────────────────────────────────────────────────────

    private final DonutSMPPlugin plugin;
    private final File file;
    private final List<Entry> entries = new ArrayList<>();
    private long nextSeq = 1;
    private boolean firstRun;

    public PlacementRegistry(DonutSMPPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "placements.txt");
        this.firstRun = !file.exists();
        load();
    }

    // ── Registering ──────────────────────────────────────────────────────────

    public Entry register(String kind, String key, World world,
                          int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (world == null) return null;
        Entry e = new Entry(nextSeq++, kind, sanitize(key), world.getName(), minX, minY, minZ, maxX, maxY, maxZ);
        entries.add(e);
        save();
        return e;
    }

    /** Registers a box given as an origin corner plus a size (like a pasted schematic). */
    public Entry registerOrigin(String kind, String key, Location origin, int sizeX, int sizeY, int sizeZ) {
        if (origin == null || origin.getWorld() == null) return null;
        int ox = origin.getBlockX(), oy = origin.getBlockY(), oz = origin.getBlockZ();
        return register(kind, key, origin.getWorld(), ox, oy, oz,
                ox + Math.max(1, sizeX) - 1, oy + Math.max(1, sizeY) - 1, oz + Math.max(1, sizeZ) - 1);
    }

    /** Registers a box given relative to a centre point: {x0, x1, y0, y1, z0, z1} offsets. */
    public Entry registerAround(String kind, String key, Location centre, int[] rel) {
        if (centre == null || centre.getWorld() == null) return null;
        int cx = centre.getBlockX(), cy = centre.getBlockY(), cz = centre.getBlockZ();
        return register(kind, key, centre.getWorld(),
                cx + rel[0], cy + rel[2], cz + rel[4], cx + rel[1], cy + rel[3], cz + rel[5]);
    }

    public int size() { return entries.size(); }

    public List<Entry> all() { return Collections.unmodifiableList(entries); }

    // ── Spacing checks ───────────────────────────────────────────────────────

    /** Edge-to-edge XZ distance between two footprints (0 if they touch or overlap). */
    static int gapBetween(int aMinX, int aMinZ, int aMaxX, int aMaxZ,
                          int bMinX, int bMinZ, int bMaxX, int bMaxZ) {
        int dx = Math.max(0, Math.max(bMinX - aMaxX, aMinX - bMaxX));
        int dz = Math.max(0, Math.max(bMinZ - aMaxZ, aMinZ - bMaxZ));
        return (int) Math.min(Integer.MAX_VALUE, Math.round(Math.sqrt((double) dx * dx + (double) dz * dz)));
    }

    /** The closest existing placement to this footprint in the same world, or null if there is none. */
    public Nearby findNearest(World world, int minX, int minZ, int maxX, int maxZ) {
        if (world == null) return null;
        Nearby best = null;
        for (Entry e : entries) {
            if (!e.worldName.equals(world.getName())) continue;
            int g = gapBetween(minX, minZ, maxX, maxZ, e.minX, e.minZ, e.maxX, e.maxZ);
            if (best == null || g < best.gap) best = new Nearby(e, g);
        }
        return best;
    }

    /** True if no existing placement is within {@code gap} blocks (edge-to-edge) of this footprint. */
    public boolean isClear(World world, int minX, int minZ, int maxX, int maxZ, int gap) {
        if (world == null) return true;
        for (Entry e : entries) {
            if (!e.worldName.equals(world.getName())) continue;
            if (gapBetween(minX, minZ, maxX, maxZ, e.minX, e.minZ, e.maxX, e.maxZ) < gap) return false;
        }
        return true;
    }

    /** Configured spacing in blocks (base-spacing.min-gap), or 0 if spacing is switched off. */
    public int configuredGap() {
        if (!plugin.getConfig().getBoolean("base-spacing.enabled", true)) return 0;
        return Math.max(0, plugin.getConfig().getInt("base-spacing.min-gap", 150));
    }

    // ── Finding crowded placements (for /removebase) ─────────────────────────

    /**
     * Works out which placements to delete so that nothing is closer than {@code minGap} to anything else.
     * Farms and hand-placed things are kept first, then older placements beat newer ones, so in every
     * cluster the oldest survives and the later ones are removed.
     */
    public List<Removal> findCrowded(int minGap) {
        List<Entry> order = new ArrayList<>(entries);
        order.sort(Comparator.comparing((Entry e) -> !isProtected(e.kind)).thenComparingLong(e -> e.seq));

        List<Entry> kept = new ArrayList<>();
        List<Removal> out = new ArrayList<>();
        for (Entry e : order) {
            Entry clash = null;
            int clashGap = Integer.MAX_VALUE;
            for (Entry k : kept) {
                if (!k.worldName.equals(e.worldName)) continue;
                int g = gapBetween(e.minX, e.minZ, e.maxX, e.maxZ, k.minX, k.minZ, k.maxX, k.maxZ);
                if (g < minGap && g < clashGap) { clash = k; clashGap = g; }
            }
            if (clash != null && !isProtected(e.kind)) out.add(new Removal(e, clash, clashGap));
            else kept.add(e);
        }
        return out;
    }

    // ── Removing ─────────────────────────────────────────────────────────────

    /**
     * Forgets the given placements everywhere (registry, no-mob zones, StaffWatch bases, activity zones),
     * then wipes their blocks a slice at a time so the server doesn't lag. {@code done} runs when finished.
     */
    public void removeAll(List<Entry> list, Runnable done) {
        Deque<Entry> queue = new ArrayDeque<>();
        for (Entry e : list) {
            if (plugin.getServer().getWorld(e.worldName) == null) continue; // world not loaded - leave it alone
            forget(e);
            queue.add(e);
        }
        runNext(queue, done);
    }

    private void forget(Entry e) {
        entries.remove(e);
        save();
        if (plugin.getNoSpawnZones() != null)
            plugin.getNoSpawnZones().removeContainedIn(e.worldName, e.minX, e.minY, e.minZ, e.maxX, e.maxY, e.maxZ);
        World w = plugin.getServer().getWorld(e.worldName);
        if (plugin.getStaffWatcher() != null && w != null)
            plugin.getStaffWatcher().unregisterBasesIn(w, e.minX - 4, e.minZ - 4, e.maxX + 4, e.maxZ + 4);
        if (plugin.getActivityTracker() != null && w != null)
            plugin.getActivityTracker().removeActivityZonesIn(w, e.minX - 4, e.minZ - 4, e.maxX + 4, e.maxZ + 4);
    }

    private void runNext(Deque<Entry> queue, Runnable done) {
        Entry e = queue.poll();
        if (e == null) { if (done != null) done.run(); return; }
        World w = plugin.getServer().getWorld(e.worldName);
        if (w == null) { runNext(queue, done); return; }
        clearBox(w, e, () -> runNext(queue, done));
    }

    /** Turns the box back into natural-looking rock (stone/deepslate below the surface, air above it). */
    private void clearBox(World w, Entry e, Runnable next) {
        try {
            BoundingBox bb = new BoundingBox(e.minX, e.minY, e.minZ, e.maxX + 1, e.maxY + 1, e.maxZ + 1);
            for (Entity en : w.getNearbyEntities(bb)) {
                if (!(en instanceof Player)) en.remove();
            }
        } catch (Exception ex) {
            plugin.getLogger().fine("Could not clear entities in removed placement: " + ex);
        }

        final int surface = naturalSurface(w, e);
        final int sx = e.maxX - e.minX + 1;
        final int sz = e.maxZ - e.minZ + 1;
        final long layer = (long) sx * sz;
        final long total = layer * (e.maxY - e.minY + 1);
        final int perTick = Math.max(1000, plugin.getConfig().getInt("removebase.blocks-per-tick", 20000));
        final int minH = w.getMinHeight();
        final int maxH = w.getMaxHeight();

        new BukkitRunnable() {
            long idx = 0;

            @Override
            public void run() {
                long end = Math.min(total, idx + perTick);
                for (; idx < end; idx++) {
                    int x = e.minX + (int) (idx % sx);
                    int z = e.minZ + (int) ((idx / sx) % sz);
                    int y = e.minY + (int) (idx / layer);
                    if (y < minH || y >= maxH) continue;
                    Material fill = (y <= surface - 3)
                            ? (y < 0 ? Material.DEEPSLATE : Material.STONE)
                            : Material.AIR;
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() != fill) b.setType(fill, false);
                }
                if (idx >= total) {
                    cancel();
                    plugin.getLogger().info("[removebase] Removed " + e.describe());
                    next.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Height of the natural ground just outside the box (lowest of 8 sample points around it). */
    private int naturalSurface(World w, Entry e) {
        int cx = e.centerX(), cz = e.centerZ();
        int[][] pts = {
            {e.minX - 3, e.minZ - 3}, {e.maxX + 3, e.minZ - 3}, {e.minX - 3, e.maxZ + 3}, {e.maxX + 3, e.maxZ + 3},
            {cx, e.minZ - 3}, {cx, e.maxZ + 3}, {e.minX - 3, cz}, {e.maxX + 3, cz}
        };
        int best = Integer.MAX_VALUE;
        for (int[] p : pts) {
            int h = w.getHighestBlockYAt(p[0], p[1], HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (h < best) best = h;
        }
        return best == Integer.MAX_VALUE ? w.getSeaLevel() : best;
    }

    // ── Legacy import (worlds generated before this registry existed) ────────

    /**
     * On the very first run, seeds the registry from the old schematic-zones.txt (every pasted schematic)
     * and farms.yml, so /removebase and the spacing checks know about bases that already exist.
     * Procedural bases and stashes from older versions were never recorded, so they can't be recovered.
     */
    public void importLegacy(NoSpawnZones zones, FarmManager farms) {
        if (!firstRun) return;
        firstRun = false;
        int imported = 0;
        if (zones != null) {
            for (Map.Entry<String, List<int[]>> we : zones.snapshot().entrySet()) {
                World w = plugin.getServer().getWorld(we.getKey());
                if (w == null) continue;
                for (int[] b : we.getValue()) {
                    String kind = SCHEMATIC;
                    String key = "legacy";
                    if (farms != null) {
                        for (FarmManager.PlacedFarm pf : farms.getPlacedFarms()) {
                            if (pf.world.getName().equals(we.getKey()) && pf.ox == b[0] && pf.oy == b[1] && pf.oz == b[2]) {
                                kind = FARM; key = pf.key; break;
                            }
                        }
                    }
                    entries.add(new Entry(nextSeq++, kind, key, we.getKey(), b[0], b[1], b[2], b[3], b[4], b[5]));
                    imported++;
                }
            }
        }
        save();
        if (imported > 0) plugin.getLogger().info("[placements] Imported " + imported + " existing schematic(s) from schematic-zones.txt.");
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private static String sanitize(String s) {
        return s == null ? "unknown" : s.replace(",", "_").replace("\n", "_").trim();
    }

    private void load() {
        if (!file.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split(",");
                if (p.length != 10) continue;
                try {
                    long seq = Long.parseLong(p[0]);
                    entries.add(new Entry(seq, p[1], p[2], p[3],
                            Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]),
                            Integer.parseInt(p[7]), Integer.parseInt(p[8]), Integer.parseInt(p[9])));
                    nextSeq = Math.max(nextSeq, seq + 1);
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not read placements.txt: " + ex.getMessage());
        }
    }

    private void save() {
        try {
            file.getParentFile().mkdirs();
            try (PrintWriter w = new PrintWriter(new FileWriter(file))) {
                for (Entry e : entries) {
                    w.println(e.seq + "," + e.kind + "," + e.key + "," + e.worldName + ","
                            + e.minX + "," + e.minY + "," + e.minZ + "," + e.maxX + "," + e.maxY + "," + e.maxZ);
                }
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save placements.txt: " + ex.getMessage());
        }
    }
}
