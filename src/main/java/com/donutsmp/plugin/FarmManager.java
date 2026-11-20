package com.donutsmp.plugin;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.Switch;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Manages farm schematics separately from stash/base schematics.
 *
 * Key responsibilities:
 *   1. FARM REGISTRY     — static list of all known farm keys + metadata
 *   2. PLACEMENT TRACKING — remembers origin + size of every placed farm
 *                           (persisted to farms.yml so restarts don't forget)
 *   3. ON / OFF TOGGLE   — scans the placed bounding box for Lever blocks and
 *                          flips their powered state; also freezes/resumes the
 *                          redstone clock by swapping REDSTONE_BLOCK ↔ STONE
 *                          at the clock driver position for farms that use one.
 *   4. COMMANDS          — /listfarms, /unusedfarms, /addfarm, /spawnfarm,
 *                          /farmtoggle
 */
public class FarmManager {

    // =========================================================================
    // Farm metadata
    // =========================================================================

    public static class FarmMeta {
        public final String key;
        public final String displayName;
        public final String filename;
        public final String type;        // kelp | cobblestone | bamboo | misc
        public final String description;
        public final int sizeX, sizeY, sizeZ;

        public FarmMeta(String key, String displayName, String filename,
                        String type, String description, int sx, int sy, int sz) {
            this.key = key; this.displayName = displayName; this.filename = filename;
            this.type = type; this.description = description;
            this.sizeX = sx; this.sizeY = sy; this.sizeZ = sz;
        }
    }

    // All registered farms — keyed by short command name
    public static final Map<String, FarmMeta> FARMS = new LinkedHashMap<>();

    static {
        // Sizes confirmed from litematic metadata (sizeX x sizeY x sizeZ)
        FARMS.put("kelppress",  new FarmMeta("kelppress",  "Kelppress v7",                 "Kelppress_v7.litematic",                  "kelp",        "Automated kelp press farm, v7",             29, 26,  67));
        FARMS.put("kelpfarm",   new FarmMeta("kelpfarm",   "Ravvix Maccas Kelp Farm",      "Ravvix_Maccas_Kelpfarm.litematic",        "kelp",        "Ravvix's McDonald's-themed kelp farm",       55, 28,  27));
        FARMS.put("bamboofarm", new FarmMeta("bamboofarm", "16-Module Walmart Bamboo Farm", "16_module_Walmart_Bamboo_Farm.litematic", "bamboo",      "16-module Walmart-themed bamboo farm",        33, 41,  34));
        FARMS.put("cobble64",   new FarmMeta("cobble64",   "64-Module Cobblestone Farm",   "64_module_cobble_farm.litematic",         "cobblestone", "64-module cobblestone generator farm",        77, 30,  84));
        FARMS.put("cobble100",  new FarmMeta("cobble100",  "100-Module Cobblestone Farm",  "100_module_cobblestone_farm.litematic",   "cobblestone", "100-module cobblestone generator farm",       97, 35, 100));
        FARMS.put("cobblejoin", new FarmMeta("cobblejoin", "Knoster Cobble Join (DonutSMP)", "knoster-intermediate-cobblejoin-donutsmp-schematic-5fd5c49d.litematic", "cobblestone", "Knoster's intermediate cobble join schematic for DonutSMP", 42, 19, 41));
    }

    // =========================================================================
    // Placed-farm record
    // =========================================================================

    /** Everything we need to toggle/query a farm that has been placed in the world. */
    public static class PlacedFarm {
        public final String key;
        public final World world;
        public final int ox, oy, oz;   // origin corner (NW bottom) from /spawnfarm
        public final int sx, sy, sz;   // bounding box size
        public boolean enabled;         // true = levers ON, farm running

        public PlacedFarm(String key, World world, int ox, int oy, int oz,
                          int sx, int sy, int sz, boolean enabled) {
            this.key = key; this.world = world;
            this.ox = ox; this.oy = oy; this.oz = oz;
            this.sx = sx; this.sy = sy; this.sz = sz;
            this.enabled = enabled;
        }
    }

    // =========================================================================
    // Fields
    // =========================================================================

    private final DonutSMPPlugin plugin;
    private final LitematicLoader litematicLoader;

    // key -> placed record (one record per key; re-spawning overwrites)
    private final Map<String, PlacedFarm> placedFarms = new LinkedHashMap<>();

    private File farmsFile;
    private YamlConfiguration farmsConfig;

    // =========================================================================
    // Constructor & persistence
    // =========================================================================

    public FarmManager(DonutSMPPlugin plugin, LitematicLoader litematicLoader) {
        this.plugin = plugin;
        this.litematicLoader = litematicLoader;
        loadFarmsFile();
    }

    private void loadFarmsFile() {
        farmsFile = new File(plugin.getDataFolder(), "farms.yml");
        farmsConfig = farmsFile.exists() ? YamlConfiguration.loadConfiguration(farmsFile) : new YamlConfiguration();

        // Re-hydrate PlacedFarm records from disk
        for (String key : farmsConfig.getKeys(false)) {
            String worldName = farmsConfig.getString(key + ".world", "world");
            World w = plugin.getServer().getWorld(worldName);
            if (w == null) continue; // world not loaded yet — skip silently
            int ox = farmsConfig.getInt(key + ".ox");
            int oy = farmsConfig.getInt(key + ".oy");
            int oz = farmsConfig.getInt(key + ".oz");
            int sx = farmsConfig.getInt(key + ".sx");
            int sy = farmsConfig.getInt(key + ".sy");
            int sz = farmsConfig.getInt(key + ".sz");
            boolean enabled = farmsConfig.getBoolean(key + ".enabled", true);
            placedFarms.put(key, new PlacedFarm(key, w, ox, oy, oz, sx, sy, sz, enabled));
        }
    }

    private void saveFarmsFile() {
        for (PlacedFarm pf : placedFarms.values()) {
            farmsConfig.set(pf.key + ".world",   pf.world.getName());
            farmsConfig.set(pf.key + ".ox",      pf.ox);
            farmsConfig.set(pf.key + ".oy",      pf.oy);
            farmsConfig.set(pf.key + ".oz",      pf.oz);
            farmsConfig.set(pf.key + ".sx",      pf.sx);
            farmsConfig.set(pf.key + ".sy",      pf.sy);
            farmsConfig.set(pf.key + ".sz",      pf.sz);
            farmsConfig.set(pf.key + ".enabled", pf.enabled);
        }
        try { farmsConfig.save(farmsFile); }
        catch (IOException e) { plugin.getLogger().warning("Could not save farms.yml: " + e.getMessage()); }
    }

    // =========================================================================
    // /listfarms [type]
    // =========================================================================

    public void listFarms(CommandSender sender, String filter) {
        sender.sendMessage(ChatColor.GOLD + "=== DonutSMP Farms" +
                (filter != null ? " [" + filter + "]" : "") + " ===");

        int shown = 0;
        for (FarmMeta f : FARMS.values()) {
            if (filter != null && !f.type.equalsIgnoreCase(filter) && !f.key.contains(filter.toLowerCase())) continue;
            PlacedFarm pf = placedFarms.get(f.key);
            String status;
            if (pf == null) {
                status = ChatColor.RED + "✗ not placed";
            } else if (pf.enabled) {
                status = ChatColor.GREEN + "▶ ON";
            } else {
                status = ChatColor.YELLOW + "■ OFF";
            }
            sender.sendMessage(" " + status + ChatColor.YELLOW + " " + f.key
                    + ChatColor.WHITE + " — " + f.displayName
                    + ChatColor.GRAY + " (" + f.type + ") " + f.description);
            shown++;
        }
        if (shown == 0) sender.sendMessage(ChatColor.RED + "No farms match filter '" + filter + "'.");
        sender.sendMessage(ChatColor.GRAY + "▶ ON  ■ OFF  ✗ not placed   " + shown + " farm(s) listed.");
        sender.sendMessage(ChatColor.GRAY + "Use /farmtoggle <key> to start or stop a farm.");
    }

    // =========================================================================
    // /unusedfarms
    // =========================================================================

    public void listUnusedFarms(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Unused Farms (not yet placed) ===");
        int count = 0;
        for (FarmMeta f : FARMS.values()) {
            if (!placedFarms.containsKey(f.key)) {
                sender.sendMessage(ChatColor.YELLOW + " " + f.key
                        + ChatColor.WHITE + " — " + f.displayName
                        + ChatColor.GRAY + " (" + f.type + ") " + f.description);
                count++;
            }
        }
        if (count == 0) sender.sendMessage(ChatColor.GREEN + "All farms have been placed!");
        else sender.sendMessage(ChatColor.GRAY + count + " unused farm(s).  Use /spawnfarm <key> to place one.");
    }

    // =========================================================================
    // /addfarm <key> <filename> [type] [description...]
    // =========================================================================

    public boolean addFarm(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /addfarm <key> <filename.litematic> [type] [description]");
            sender.sendMessage(ChatColor.GRAY + "Example: /addfarm sugarcane Sugar_Farm.litematic misc 'Sugar cane farm 64 wide'");
            return false;
        }
        String key = args[0].toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (key.isEmpty()) { sender.sendMessage(ChatColor.RED + "Key must contain letters or numbers."); return false; }
        if (FARMS.containsKey(key)) { sender.sendMessage(ChatColor.RED + "Farm '" + key + "' already exists. Use /listfarms."); return false; }

        String filename = args[1];
        String type = args.length > 2 ? args[2] : "misc";
        StringBuilder db = new StringBuilder();
        for (int i = 3; i < args.length; i++) { if (db.length() > 0) db.append(' '); db.append(args[i]); }
        String desc = db.length() > 0 ? db.toString() : "Custom farm";

        File f = new File(plugin.getDataFolder(), "schematics/" + filename);
        if (!f.exists()) {
            sender.sendMessage(ChatColor.RED + "File not found: plugins/DonutSMPWorld/schematics/" + filename);
            return false;
        }

        FARMS.put(key, new FarmMeta(key, filename.replace(".litematic", "").replace("_", " "), filename, type, desc, 0, 0, 0));
        LitematicLoader.SCHEMATICS.put(key, new LitematicLoader.SchematicMeta(filename, 1, desc, "main", 0, 0, 0, false));
        sender.sendMessage(ChatColor.GREEN + "Registered farm '" + key + "'. Use /spawnfarm " + key + " to place it.");
        return true;
    }

    // =========================================================================
    // /spawnfarm <key>
    // =========================================================================

    public void spawnFarm(Player player, String key) {
        key = key.toLowerCase();
        FarmMeta meta = FARMS.get(key);
        if (meta == null) {
            player.sendMessage(ChatColor.RED + "Unknown farm '" + key + "'. Use /listfarms.");
            return;
        }

        // Ensure it's in LitematicLoader
        if (!LitematicLoader.SCHEMATICS.containsKey(key)) {
            LitematicLoader.SCHEMATICS.put(key, new LitematicLoader.SchematicMeta(
                    meta.filename, 1, meta.displayName, "main", meta.sizeX, meta.sizeY, meta.sizeZ, false));
        }

        player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.YELLOW
                + "Placing " + meta.displayName + " at your location...");

        Location loc = player.getLocation();
        final String fkey = key;

        // Warn (but still place - an admin chose this spot) if it's right next to something else
        PlacementRegistry reg = plugin.getPlacementRegistry();
        if (reg != null && reg.configuredGap() > 0) {
            PlacementRegistry.Nearby near = reg.findNearest(loc.getWorld(), loc.getBlockX(), loc.getBlockZ(),
                    loc.getBlockX() + Math.max(1, meta.sizeX) - 1, loc.getBlockZ() + Math.max(1, meta.sizeZ) - 1);
            if (near != null && near.gap < reg.configuredGap()) {
                player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.RED + "Heads up: this is only "
                        + near.gap + " blocks from " + near.entry.describe() + ".");
            }
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            boolean ok = litematicLoader.pasteLitematic(fkey, loc);
            if (ok) {
                // Determine actual size: use metadata if available, else fall back to FarmMeta
                int sx = meta.sizeX, sy = meta.sizeY, sz = meta.sizeZ;
                LitematicLoader.SchematicMeta sm = LitematicLoader.SCHEMATICS.get(fkey);
                if (sm != null && sm.sizeX > 0) { sx = sm.sizeX; sy = sm.sizeY; sz = sm.sizeZ; }

                PlacedFarm pf = new PlacedFarm(fkey, loc.getWorld(),
                        loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                        sx, sy, sz, true);
                placedFarms.put(fkey, pf);
                saveFarmsFile();

                player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN
                        + meta.displayName + " placed at "
                        + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()
                        + "!  Use /farmtoggle " + fkey + " to turn it off.");
            } else {
                player.sendMessage(ChatColor.RED + "Failed to place " + meta.displayName
                        + " — check that the .litematic is in the schematics folder.");
            }
        });
    }

    // =========================================================================
    // /farmtoggle <key> [on|off]
    // =========================================================================

    /**
     * Toggle (or force on/off) a placed farm.
     *
     * Mechanism:
     *   1. Walk every block in the farm's bounding box.
     *   2. Any LEVER block found → flip (or force) its `powered` blockstate.
     *   3. Any REDSTONE_BLOCK found → swap to STONE (off) or back to REDSTONE_BLOCK (on).
     *      These act as clock drivers in cobblestone and bamboo farms.
     *   4. The result is that the redstone clock stops/starts, pistons freeze, and
     *      the farm stops producing.  Lava and water stay in place so the farm
     *      can be safely restarted without needing to be re-placed.
     *
     * Why levers + redstone_blocks (and NOT just removing water/lava):
     *   - Removing lava/water breaks the farm permanently; it needs to be re-placed.
     *   - Levers are the designed on/off switch; flipping them is exactly what a
     *     player would do standing at the farm.
     *   - Redstone blocks in clock positions need to go too — a lever alone won't
     *     stop a self-powered clock that uses a redstone block as the pulse source.
     *
     * Runs async chunk scan → main-thread block edits to stay Paper-safe.
     */
    public void toggleFarm(CommandSender sender, String key, Boolean forceState) {
        key = key.toLowerCase();
        PlacedFarm pf = placedFarms.get(key);
        if (pf == null) {
            sender.sendMessage(ChatColor.RED + "Farm '" + key + "' has not been placed yet. Use /spawnfarm " + key + ".");
            return;
        }

        boolean targetEnabled = forceState != null ? forceState : !pf.enabled;
        pf.enabled = targetEnabled;
        saveFarmsFile();

        String actionWord = targetEnabled ? ChatColor.GREEN + "Starting" : ChatColor.YELLOW + "Stopping";
        sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + actionWord + ChatColor.RESET + " farm " + ChatColor.YELLOW + key + ChatColor.RESET + "...");

        final boolean enable = targetEnabled;
        final PlacedFarm farm = pf;

        // All block edits must be on the main thread (Paper enforces this)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            int leversFlipped = 0;
            int redstoneSwapped = 0;
            World world = farm.world;

            // Ensure chunks in the farm area are loaded
            int chunkMinX = farm.ox >> 4;
            int chunkMaxX = (farm.ox + farm.sx) >> 4;
            int chunkMinZ = farm.oz >> 4;
            int chunkMaxZ = (farm.oz + farm.sz) >> 4;
            for (int cx = chunkMinX; cx <= chunkMaxX; cx++)
                for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++)
                    world.loadChunk(cx, cz, false);

            for (int x = farm.ox; x < farm.ox + farm.sx; x++) {
                for (int y = farm.oy; y < farm.oy + farm.sy; y++) {
                    for (int z = farm.oz; z < farm.oz + farm.sz; z++) {
                        if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
                        Block block = world.getBlockAt(x, y, z);
                        Material type = block.getType();

                        // ── Levers ──────────────────────────────────────────
                        if (type == Material.LEVER) {
                            BlockData bd = block.getBlockData();
                            if (bd instanceof Switch) {
                                Switch lever = (Switch) bd;
                                boolean wasPowered = lever.isPowered();
                                boolean wantPowered = enable;
                                if (wasPowered != wantPowered) {
                                    lever.setPowered(wantPowered);
                                    block.setBlockData(lever, true); // true = apply physics
                                    leversFlipped++;
                                }
                            }
                        }

                        // ── Redstone block clock drivers ──────────────────
                        // OFF: REDSTONE_BLOCK → STONE (inert, same hardness, looks fine)
                        // ON:  STONE → REDSTONE_BLOCK  (only if this block was swapped by us)
                        // We rely on context: a stone block adjacent to observers/repeaters
                        // in a farm interior is almost certainly a swapped driver.
                        if (!enable && type == Material.REDSTONE_BLOCK) {
                            if (isInsideFarm(farm, x, y, z)) {
                                block.setType(Material.STONE, false);
                                redstoneSwapped++;
                            }
                        } else if (enable && type == Material.STONE) {
                            // Only restore if it looks like a clock driver:
                            // has at least one observer or repeater neighbour
                            if (isInsideFarm(farm, x, y, z) && hasRedstoneNeighbour(world, x, y, z)) {
                                block.setType(Material.REDSTONE_BLOCK, false);
                                redstoneSwapped++;
                            }
                        }
                    }
                }
            }

            String result = enable ? (ChatColor.GREEN + "Farm " + key + " is now ON") : (ChatColor.YELLOW + "Farm " + key + " is now OFF");
            sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + result
                    + ChatColor.GRAY + " (" + leversFlipped + " lever(s) flipped"
                    + (redstoneSwapped > 0 ? ", " + redstoneSwapped + " clock block(s) swapped" : "")
                    + ")");

            if (leversFlipped == 0 && redstoneSwapped == 0) {
                sender.sendMessage(ChatColor.GRAY + "  No lever or clock blocks found inside the bounding box."
                        + " Make sure the farm was placed with /spawnfarm (not /placeschematic).");
            }
        });
    }

    /** True if (x,y,z) is strictly inside the farm bounding box. */
    private boolean isInsideFarm(PlacedFarm farm, int x, int y, int z) {
        return x >= farm.ox && x < farm.ox + farm.sx
            && y >= farm.oy && y < farm.oy + farm.sy
            && z >= farm.oz && z < farm.oz + farm.sz;
    }

    /** True if any of the 6 face neighbours is an observer or repeater (clock-driver heuristic). */
    private boolean hasRedstoneNeighbour(World world, int x, int y, int z) {
        int[][] offsets = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] o : offsets) {
            Material m = world.getBlockAt(x + o[0], y + o[1], z + o[2]).getType();
            if (m == Material.OBSERVER || m == Material.REPEATER
                    || m == Material.COMPARATOR || m == Material.PISTON
                    || m == Material.STICKY_PISTON) return true;
        }
        return false;
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public Collection<PlacedFarm> getPlacedFarms() { return Collections.unmodifiableCollection(placedFarms.values()); }
    public boolean isPlaced(String key) { return placedFarms.containsKey(key.toLowerCase()); }
    public boolean isEnabled(String key) {
        PlacedFarm pf = placedFarms.get(key.toLowerCase());
        return pf != null && pf.enabled;
    }
    public PlacedFarm getPlaced(String key) { return placedFarms.get(key.toLowerCase()); }
}
