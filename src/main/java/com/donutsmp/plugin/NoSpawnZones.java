package com.donutsmp.plugin;

import org.bukkit.World;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.util.BoundingBox;

import java.io.*;
import java.util.*;

/**
 * Remembers the box of every pasted schematic (saved to plugins/DonutSMPWorld/schematic-zones.txt)
 * and stops mobs from spawning inside them. Config: no-mob-spawn-in-schematics (default true).
 */
public class NoSpawnZones implements Listener {

    // Spawns players/admins cause on purpose are still allowed inside zones.
    private static final Set<SpawnReason> ALLOWED = EnumSet.of(
            SpawnReason.CUSTOM, SpawnReason.COMMAND, SpawnReason.SPAWNER_EGG, SpawnReason.DISPENSE_EGG,
            SpawnReason.BUILD_IRONGOLEM, SpawnReason.BUILD_SNOWMAN, SpawnReason.BUILD_WITHER,
            SpawnReason.BREEDING, SpawnReason.EGG, SpawnReason.CURED, SpawnReason.ENDER_PEARL, SpawnReason.BEEHIVE);

    private final DonutSMPPlugin plugin;
    private final File file;
    private final Map<String, List<int[]>> zones = new HashMap<>();

    public NoSpawnZones(DonutSMPPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "schematic-zones.txt");
        load();
    }

    /** Records a pasted schematic's box (inclusive corners) and removes any hostile mobs already inside it. */
    public void add(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        zones.computeIfAbsent(world.getName(), k -> new ArrayList<>())
             .add(new int[]{minX, minY, minZ, maxX, maxY, maxZ});
        save();
        try {
            BoundingBox bb = new BoundingBox(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
            for (Entity e : world.getNearbyEntities(bb)) {
                if (e instanceof Enemy) e.remove();
            }
        } catch (Exception ex) {
            plugin.getLogger().fine("Could not clear mobs in new schematic zone: " + ex);
        }
    }

    /** Copy of every saved zone box, keyed by world name (used to seed the placement registry). */
    public Map<String, List<int[]>> snapshot() {
        Map<String, List<int[]>> copy = new HashMap<>();
        for (Map.Entry<String, List<int[]>> e : zones.entrySet()) copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        return copy;
    }

    /** Forgets every zone box that lies fully inside the given box (used when a placement is removed). */
    public void removeContainedIn(String worldName, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        List<int[]> list = zones.get(worldName);
        if (list == null) return;
        boolean changed = list.removeIf(b -> b[0] >= minX && b[1] >= minY && b[2] >= minZ
                                          && b[3] <= maxX && b[4] <= maxY && b[5] <= maxZ);
        if (changed) save();
    }

    public boolean isInside(World world, int x, int y, int z) {
        List<int[]> list = zones.get(world.getName());
        if (list == null) return false;
        for (int[] z6 : list) {
            if (x >= z6[0] && x <= z6[3] && y >= z6[1] && y <= z6[4] && z >= z6[2] && z <= z6[5]) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!plugin.getConfig().getBoolean("no-mob-spawn-in-schematics", true)) return;
        if (ALLOWED.contains(e.getSpawnReason())) return;
        if (isInside(e.getLocation().getWorld(), e.getLocation().getBlockX(), e.getLocation().getBlockY(), e.getLocation().getBlockZ())) {
            e.setCancelled(true);
        }
    }

    private void load() {
        if (!file.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split(",");
                if (p.length != 7) continue;
                int[] b = new int[6];
                for (int i = 0; i < 6; i++) b[i] = Integer.parseInt(p[i + 1].trim());
                zones.computeIfAbsent(p[0], k -> new ArrayList<>()).add(b);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read schematic-zones.txt: " + e.getMessage());
        }
    }

    private void save() {
        try {
            file.getParentFile().mkdirs();
            try (PrintWriter w = new PrintWriter(new FileWriter(file))) {
                for (Map.Entry<String, List<int[]>> e : zones.entrySet())
                    for (int[] b : e.getValue())
                        w.println(e.getKey() + "," + b[0] + "," + b[1] + "," + b[2] + "," + b[3] + "," + b[4] + "," + b[5]);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save schematic-zones.txt: " + e.getMessage());
        }
    }
}
