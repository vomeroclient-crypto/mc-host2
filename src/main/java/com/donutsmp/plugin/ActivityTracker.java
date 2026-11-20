package com.donutsmp.plugin;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.*;

/**
 * Tracks player activity and simulates "lived-in" zones.
 * Geodes cluster around these zones to suggest past activity.
 *
 * PERFORMANCE (v7): the old version listened to PlayerMoveEvent (fires many
 * times per second per player) and created Chunk objects on every call.
 * Now movement is sampled once every few seconds with plain integer math,
 * and block events use integer chunk coordinates only.
 */
public class ActivityTracker implements Listener {

    private static final long SAMPLE_TICKS = 100L;   // sample players every 5 s
    private static final int  MAX_TRACKED_CHUNKS = 20000;

    private final DonutSMPPlugin plugin;
    private final List<Location> activityZones = new ArrayList<>();
    private final Map<Long, Integer> chunkActivity = new HashMap<>();
    private final int maxRuntimeZones;
    private int runtimeZones = 0;

    // Pre-seeded fake activity zones (simulates where bases "were built")
    private static final int[][] FAKE_ACTIVITY_OFFSETS = {
        {800, 800}, {-1200, 400}, {500, -900}, {-600, -700},
        {1500, -300}, {-1800, 1200}, {300, 1600}, {-900, -1500},
        {2000, 500}, {-500, 2200}, {1100, -1800}, {-1600, -200}
    };

    public ActivityTracker(DonutSMPPlugin plugin) {
        this.plugin = plugin;
        this.maxRuntimeZones = plugin.getConfig().getInt("performance.max-runtime-activity-zones", 30);
        seedFakeActivityZones();
        startSampler();
    }

    private void seedFakeActivityZones() {
        World world = plugin.getServer().getWorlds().get(0);
        int border = plugin.getConfig().getInt("world-border", 10000);
        Random rand = new Random();

        for (int[] offset : FAKE_ACTIVITY_OFFSETS) {
            int x = offset[0];
            int z = offset[1];
            if (Math.abs(x) < border && Math.abs(z) < border) {
                activityZones.add(new Location(world, x, 64, z));
                for (int i = 0; i < 3; i++) {
                    int dx = rand.nextInt(200) - 100;
                    int dz = rand.nextInt(200) - 100;
                    activityZones.add(new Location(world, x + dx, 64, z + dz));
                }
            }
        }
        plugin.getLogger().info("Seeded " + activityZones.size() + " fake activity zones.");
    }

    /** One cheap repeating task instead of a listener on every player movement. */
    private void startSampler() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                Location l = p.getLocation();
                record(l.getWorld(), l.getBlockX() >> 4, l.getBlockZ() >> 4,
                       l.getBlockX(), l.getBlockZ());
            }
        }, SAMPLE_TICKS, SAMPLE_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        var b = event.getBlock();
        record(b.getWorld(), b.getX() >> 4, b.getZ() >> 4, b.getX(), b.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        var b = event.getBlock();
        record(b.getWorld(), b.getX() >> 4, b.getZ() >> 4, b.getX(), b.getZ());
    }

    private void record(World world, int cx, int cz, int bx, int bz) {
        if (runtimeZones >= maxRuntimeZones) return;           // enough zones, stop tracking
        if (chunkActivity.size() > MAX_TRACKED_CHUNKS) chunkActivity.clear();  // hard memory cap

        long key = (((long) cx) << 32) ^ (cz & 0xffffffffL);
        int count = chunkActivity.merge(key, 1, Integer::sum);

        if (count == 50) {
            Location loc = new Location(world, bx, 64, bz);
            activityZones.add(loc);
            runtimeZones++;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                GeodeGenerator gen = new GeodeGenerator(plugin, this);
                gen.generateGeodesNear(loc, 3);
            }, 100L);
        }
    }

    public List<Location> getActivityZones() {
        return Collections.unmodifiableList(activityZones);
    }

    public int getZoneCount() {
        return activityZones.size();
    }

    public void addActivityZone(Location loc) {
        activityZones.add(loc);
    }

    /** Drops activity zones inside this XZ box so geodes stop clustering around a removed base. */
    public void removeActivityZonesIn(World world, int minX, int minZ, int maxX, int maxZ) {
        activityZones.removeIf(l -> l.getWorld() != null && l.getWorld().equals(world)
                && l.getBlockX() >= minX && l.getBlockX() <= maxX
                && l.getBlockZ() >= minZ && l.getBlockZ() <= maxZ);
    }
}
