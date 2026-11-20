package com.donutsmp.plugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.nio.ByteBuffer;

/**
 * Reads .litematic files (NBT/gzip format) and pastes them into the world.
 *
 * Litematic format:
 *   GZip → NBT compound
 *   Root → Metadata (EnclosingSize x/y/z), Regions (map of region name → region)
 *   Region → BlockStatePalette (list of block state compounds), BlockStates (long array),
 *             Size (x/y/z), Position (x/y/z)
 *
 * BlockStates is a packed long array where each entry is bitsPerBlock bits wide.
 * Index = y * sizeX * sizeZ + z * sizeX + x
 */
public class LitematicLoader {

    private final DonutSMPPlugin plugin;

    // Known schematics with metadata
    public static final Map<String, SchematicMeta> SCHEMATICS = new LinkedHashMap<>();

    static {
        // name, filename, difficulty (1=easy, 3=hardest), description, loot tier
        SCHEMATICS.put("drdonut",   new SchematicMeta("DrDonut_Mega_Nether_Storage_Stash.litematic",  3, "DrDonut's Mega Nether Storage", "media",  22, 22, 65, true));
        SCHEMATICS.put("greek",     new SchematicMeta("Greek_stash.litematic",                        3, "Greek's Hidden Stash",          "baltop", 24, 37, 69, true));
        SCHEMATICS.put("ice",       new SchematicMeta("ice_stash.litematic",                          2, "Ice Stash",                     "main",   50, 17, 19, false));
        SCHEMATICS.put("redafk",    new SchematicMeta("Red_Afk_Area.litematic",                       1, "Red's AFK Area",                "main",   57, 63, 71, false));
        SCHEMATICS.put("gambling",  new SchematicMeta("Green_gambling_base.litematic",                1, "Green's Gambling Base",         "nomad",  20, 15, 23, false));
        SCHEMATICS.put("lacchez",   new SchematicMeta("Lacchez_Spot.litematic",                       2, "Lacchez's Spot",                "main",   25, 15, 23, false));
        SCHEMATICS.put("dils",      new SchematicMeta("Dils_White.litematic",                         2, "Dil's White Base",              "main",   51, 36, 51, false));
        SCHEMATICS.put("regear",    new SchematicMeta("regear_base.litematic",                         2, "Regear Base",                   "regear", 21, 20, 38, true));
        SCHEMATICS.put("purplex",   new SchematicMeta("purplex_s_purple_radioactive_base.litematic",   3, "Purplex's Purple Radioactive Base", "baltop", 84, 37, 67, true));

        // ── Imported from all_schematics_final.zip ──
        SCHEMATICS.put("bastion",   new SchematicMeta("Bastion_Base_Stash.litematic",                  2, "Bastion Base Stash",             "main",   45, 49, 45, false));
        SCHEMATICS.put("coliseum",  new SchematicMeta("Big_Coliseum_Gamble_Base.litematic",            3, "Big Coliseum Gamble Base",       "baltop", 55, 30, 54, true));
        SCHEMATICS.put("sandstone", new SchematicMeta("Sandstone_Mega_Base.litematic",                 3, "Sandstone Mega Base",            "baltop", 46, 26, 50, true));
        SCHEMATICS.put("voidview",  new SchematicMeta("VOIDVIEW_SCHEMATIC.litematic",                  3, "VoidView Base",                  "baltop", 53, 34, 87, true));
        SCHEMATICS.put("voidxmas",  new SchematicMeta("Void_Veiw_Christmas_Base.litematic",            3, "VoidView Christmas Base",        "baltop", 67, 37, 84, true));
        SCHEMATICS.put("cathedral", new SchematicMeta("Voltz_s_Legendary_Blackstone_Cathedral.litematic", 3, "Voltz's Legendary Blackstone Cathedral", "media", 148, 62, 87, true));
        SCHEMATICS.put("casino",    new SchematicMeta("Water_Casino.litematic",                        1, "Water Casino",                   "nomad",  53, 19, 54, false));
        SCHEMATICS.put("mud",       new SchematicMeta("mud_stash_kelpk1ngTT.litematic",                1, "Mud Stash (kelpk1ngTT)",         "nomad",  44, 24, 31, false));

        // ── Imported batch 2 ──
        SCHEMATICS.put("afkwhite",     new SchematicMeta("AFK_Zone_White_by_Edwins.litematic",       1, "AFK Zone White (Edwins)",    "main",   56, 55, 60,  false));
        SCHEMATICS.put("afkblack",     new SchematicMeta("Small_Afk_Area_Black.litematic",           1, "Small AFK Area Black",       "main",   36, 21, 36,  false));
        SCHEMATICS.put("gambleregear", new SchematicMeta("0823ks_gamble_and_regear_base.litematic",  2, "0823ks Gamble and Regear Base", "regear", 15, 15, 41, true));
        SCHEMATICS.put("bluegamble",   new SchematicMeta("sucsve_mini_blue_gamble_base.litematic",   1, "sucsve Mini Blue Gamble Base", "nomad", 51, 21, 51, false));
        SCHEMATICS.put("redgamble",    new SchematicMeta("sucsve_mini_red_gamble_base.litematic",    1, "sucsve Mini Red Gamble Base",  "nomad", 51, 21, 51, false));
        SCHEMATICS.put("amethyststasis", new SchematicMeta("Amethyst_stash_with_stasis.litematic",   2, "Amethyst Stash with Stasis", "baltop", 27, 15, 17, false));
        SCHEMATICS.put("greekmega",    new SchematicMeta("AntToeKnee69s_Greek_Megabase.litematic",   3, "AntToeKnee69's Greek Megabase", "baltop", 29, 36, 50, true));
        SCHEMATICS.put("pokemongym",   new SchematicMeta("0823ks_Pokemon_Gym.litematic",             3, "0823ks Pokemon Gym",         "baltop", 104, 44, 105, true));
        SCHEMATICS.put("schem1378",    new SchematicMeta("Schematic_1378.litematic",                 3, "Schematic 1378",             "baltop", 63, 38, 104, true));

        // ── Imported batch 3 ──
        SCHEMATICS.put("channduo",    new SchematicMeta("Chann_Duo_Black_White_Stash_V2.litematic",        2, "Chann Duo Black White Stash V2", "main",   37, 17, 19,  false));
        SCHEMATICS.put("allanlunar",  new SchematicMeta("Allan_x_Lunar_Stash.litematic",                   2, "Allan x Lunar Stash",            "baltop", 45, 29, 53,  false));
        SCHEMATICS.put("blackgreen",  new SchematicMeta("Black_Green_Stash.litematic",                     3, "Black Green Stash",              "baltop", 90, 72, 137, true));
        SCHEMATICS.put("firelags",    new SchematicMeta("FireLags_Black_And_Red_Gamble_Base.litematic",    1, "FireLags Black and Red Gamble Base", "nomad", 69, 32, 69, false));
        SCHEMATICS.put("lushvillage", new SchematicMeta("Lush_Cave_village.litematic",                     3, "Lush Cave Village",              "baltop", 84, 43, 88,  true));
        SCHEMATICS.put("mario",       new SchematicMeta("Mario_Base_Updated.litematic",                    3, "Mario Base",                     "media",  177, 98, 155, true));

        // ── Imported batch 4 ──
        SCHEMATICS.put("spongebob",   new SchematicMeta("1-1_spongebob_base.litematic",                    3, "SpongeBob Base",                 "baltop", 69, 37, 64,  true));
        SCHEMATICS.put("kraken",      new SchematicMeta("Exu_Kracken_Stash.litematic",                     3, "Exu Kraken Stash",               "baltop", 98, 61, 109, true));
        SCHEMATICS.put("rzrcasino",   new SchematicMeta("rzrMC_s_Casino.litematic",                        1, "rzrMC's Casino",                 "nomad",  69, 43, 69,  false));
        SCHEMATICS.put("ramen",       new SchematicMeta("ramens20base.litematic",                          3, "Ramen's Base",                   "baltop", 99, 52, 58,  true));
        SCHEMATICS.put("diamondcastle", new SchematicMeta("Diamond_castle_schematic.litematic",            2, "Diamond Castle",                 "media",  69, 43, 69,  false));
        SCHEMATICS.put("mcqueen",     new SchematicMeta("lightning-mcqueen-stash.litematic",               3, "Lightning McQueen Stash",        "baltop", 88, 46, 115, true));
        SCHEMATICS.put("sandpearl",   new SchematicMeta("sandstone-ender-pearl-stash.litematic",           2, "Sandstone Ender Pearl Stash",    "main",   50, 37, 93,  false));
        SCHEMATICS.put("medieval",    new SchematicMeta("Medieval_Gamble_Regear_Stash.litematic",          2, "Medieval Gamble Regear Stash",   "regear", 37, 18, 53,  true));
        SCHEMATICS.put("yacht",       new SchematicMeta("Yacht_Gambling_Base_by_Skyry.litematic",          1, "Yacht Gambling Base (Skyry)",    "nomad",  37, 24, 68,  false));
        SCHEMATICS.put("gentgreen",   new SchematicMeta("Gentsmp_Small_Green_Gamble_Base.litematic",       1, "Gentsmp Small Green Gamble Base", "nomad", 83, 36, 41,  false));

        // ── New stash schematics (batch 5) ──
        SCHEMATICS.put("bluefarex",   new SchematicMeta("Blue_stash_Farex_2v.litematic",                                       2, "Blue Stash (Farex 2v)",                 "main",   0, 0, 0,  false));
        SCHEMATICS.put("drdonutsandstone", new SchematicMeta("Drdonut_s_sandstone_stash_built_by_Bubbi_and_Kyle_.litematic",   3, "DrDonut's Sandstone Stash (Bubbi & Kyle)", "media", 0, 0, 0, true));
        SCHEMATICS.put("legoninjago", new SchematicMeta("Lego_Ninjago_Stash.litematic",                                        2, "Lego Ninjago Stash",                    "baltop", 79, 43, 66, false));
        SCHEMATICS.put("prison",      new SchematicMeta("Prison_stash.litematic",                                              3, "Prison Stash",                          "baltop", 59, 47, 58, true));

        // ── Farm schematics (registered here so /placeschematic works) ──
        SCHEMATICS.put("kelppress",   new SchematicMeta("Kelppress_v7.litematic",                           1, "Kelppress v7",                   "main", 0, 0, 0, false));
        SCHEMATICS.put("kelpfarm",    new SchematicMeta("Ravvix_Maccas_Kelpfarm.litematic",                  1, "Ravvix Maccas Kelp Farm",        "main", 55, 28, 27, false));
        SCHEMATICS.put("bamboofarm",  new SchematicMeta("16_module_Walmart_Bamboo_Farm.litematic",           1, "16-Module Walmart Bamboo Farm",  "main", 0, 0, 0, false));
        SCHEMATICS.put("cobble64",    new SchematicMeta("64_module_cobble_farm.litematic",                   1, "64-Module Cobblestone Farm",     "main", 77, 30, 84, false));
        SCHEMATICS.put("cobble100",   new SchematicMeta("100_module_cobblestone_farm.litematic",             1, "100-Module Cobblestone Farm",    "main", 97, 35, 100, false));
        SCHEMATICS.put("cobblejoin",  new SchematicMeta("knoster-intermediate-cobblejoin-donutsmp-schematic-5fd5c49d.litematic", 1, "Knoster Cobble Join (DonutSMP)", "main", 42, 19, 41, false));
        SCHEMATICS.put("drdonuttbase", new SchematicMeta("Drdonutt_recrated_base.litematic",   2, "DrDonutt Recreated Base",        "baltop", 29, 32, 28, false));
        SCHEMATICS.put("tuffpearl",    new SchematicMeta("DrDonut_Tuff_Pearl_Regear.litematic", 2, "DrDonut Tuff Pearl Regear",      "regear", 31, 27, 31, true));
    }

    public LitematicLoader(DonutSMPPlugin plugin) {
        this.plugin = plugin;
    }

    // Boxes (minX,minY,minZ,maxX,maxY,maxZ) of the regions pasted by the paste in progress.
    private final List<int[]> pasteBoxes = new ArrayList<>();

    /**
     * Pastes a litematic at the given location.
     * The origin is the northwest bottom corner of the schematic.
     */
    public boolean pasteLitematic(String schematicKey, Location origin) {
        return pasteLitematic(schematicKey, origin, null);
    }

    /**
     * Same as above, but lets the caller say what kind of placement this is for the PlacementRegistry
     * (PlacementRegistry.MANUAL = an admin chose the spot, so /removebase never deletes it).
     * Pass null to pick automatically (farm keys count as farms, everything else as a schematic).
     */
    public boolean pasteLitematic(String schematicKey, Location origin, String kindOverride) {
        pasteBoxes.clear();
        SchematicMeta meta = SCHEMATICS.get(schematicKey);
        if (meta == null) {
            plugin.getLogger().warning("Unknown schematic: " + schematicKey);
            return false;
        }

        File file = new File(plugin.getDataFolder(), "schematics/" + meta.filename);
        if (!file.exists()) {
            // Not in the data folder: extract the copy bundled inside the plugin jar.
            try {
                file.getParentFile().mkdirs();
                plugin.saveResource("schematics/" + meta.filename, false);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not extract bundled schematic " + meta.filename + ": " + e.getMessage());
            }
        }
        if (!file.exists() && plugin.getResource("schematics/" + meta.filename) == null) {
            plugin.getLogger().warning("Schematic not found in plugins/DonutSMPWorld/schematics/ or inside the jar: " + meta.filename);
            return false;
        }

        try {
            plugin.getLogger().info("Loading litematic: " + meta.filename);
            NbtCompound root = readNbt(file, meta.filename);
            pasteRegions(root, origin);
            plugin.getLogger().info("Pasted " + meta.displayName + " at " + origin.getBlockX() + "," + origin.getBlockY() + "," + origin.getBlockZ());
            // After pasting, refill every chest in the schematic's bounding box
            // with the schematic's designated loot tier (so regear bases always
            // have full netherite kits, media/baltop bases have correct loot, etc.)
            if (meta.sizeX > 0 && meta.sizeY > 0 && meta.sizeZ > 0) {
                refillChests(origin, meta);
            }
            registerPlacement(schematicKey, meta, origin, kindOverride);
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to paste " + meta.filename + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /** Records the pasted box in the PlacementRegistry so spacing checks and /removebase know about it. */
    private void registerPlacement(String key, SchematicMeta meta, Location origin, String kindOverride) {
        PlacementRegistry reg = plugin.getPlacementRegistry();
        World world = origin.getWorld();
        if (reg == null || world == null) return;

        int minX, minY, minZ, maxX, maxY, maxZ;
        if (!pasteBoxes.isEmpty()) {
            minX = Integer.MAX_VALUE; minY = Integer.MAX_VALUE; minZ = Integer.MAX_VALUE;
            maxX = Integer.MIN_VALUE; maxY = Integer.MIN_VALUE; maxZ = Integer.MIN_VALUE;
            for (int[] b : pasteBoxes) {
                minX = Math.min(minX, b[0]); minY = Math.min(minY, b[1]); minZ = Math.min(minZ, b[2]);
                maxX = Math.max(maxX, b[3]); maxY = Math.max(maxY, b[4]); maxZ = Math.max(maxZ, b[5]);
            }
        } else {
            minX = origin.getBlockX(); minY = origin.getBlockY(); minZ = origin.getBlockZ();
            maxX = minX + Math.max(1, meta.sizeX) - 1;
            maxY = minY + Math.max(1, meta.sizeY) - 1;
            maxZ = minZ + Math.max(1, meta.sizeZ) - 1;
        }
        String kind = kindOverride != null ? kindOverride
                : (FarmManager.FARMS.containsKey(key) ? PlacementRegistry.FARM : PlacementRegistry.SCHEMATIC);
        reg.register(kind, key, world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Scans the bounding box of a just-pasted schematic and refills every
     * Chest (single or double) with fresh loot from the schematic's loot tier.
     * This ensures that:
     *   - regear bases always have full netherite kits + totems
     *   - media/baltop bases have the correct wealth of loot
     *   - the schematic's original chest contents (usually empty) are replaced
     */
    private void refillChests(Location origin, SchematicMeta meta) {
        Random r = new Random();
        World world = origin.getWorld();
        if (world == null) return;
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        int filled = 0;
        for (int x = ox; x < ox + meta.sizeX; x++) {
            for (int y = oy; y < oy + meta.sizeY; y++) {
                for (int z = oz; z < oz + meta.sizeZ; z++) {
                    org.bukkit.block.Block b = world.getBlockAt(x, y, z);
                    if (b.getState() instanceof org.bukkit.block.Chest) {
                        org.bukkit.block.Chest chest = (org.bukkit.block.Chest) b.getState();
                        List<ItemStack> loot = LootTable.getLoot(meta.lootTier, r);
                        java.util.Collections.shuffle(loot, r);
                        chest.getInventory().clear();
                        int slot = 0;
                        for (ItemStack item : loot) {
                            if (slot >= 27) break;
                            chest.getInventory().setItem(slot++, item);
                        }
                        // Loop loot to fill remaining empty slots (makes chest look packed)
                        if (slot < 27 && !loot.isEmpty()) {
                            for (int i = 0; slot < 27; i++, slot++) {
                                ItemStack filler = loot.get(i % loot.size()).clone();
                                if (chest.getInventory().getItem(slot) == null)
                                    chest.getInventory().setItem(slot, filler);
                            }
                        }
                        chest.update();
                        filled++;
                    }
                }
            }
        }
        if (filled > 0) {
            plugin.getLogger().info("Refilled " + filled + " chest(s) in " + meta.displayName + " with '" + meta.lootTier + "' loot.");
        }
    }

    /** Renames a schematic key (what you type in /placeschematic). Keeps order. Returns an error message or null on success. */
    public String renameKey(String oldKey, String newKey) {
        oldKey = oldKey.toLowerCase();
        newKey = newKey.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (!SCHEMATICS.containsKey(oldKey)) return "No schematic called '" + oldKey + "'.";
        if (newKey.isEmpty()) return "New name must contain letters or numbers.";
        if (!newKey.equals(oldKey) && SCHEMATICS.containsKey(newKey)) return "'" + newKey + "' is already taken.";
        LinkedHashMap<String, SchematicMeta> copy = new LinkedHashMap<>();
        for (Map.Entry<String, SchematicMeta> e : SCHEMATICS.entrySet())
            copy.put(e.getKey().equals(oldKey) ? newKey : e.getKey(), e.getValue());
        SCHEMATICS.clear();
        SCHEMATICS.putAll(copy);
        return null;
    }

    /** Re-applies saved key renames (config key-renames: ["old -> new", ...]) after registration. */
    public void applySavedRenames() {
        for (String rule : plugin.getConfig().getStringList("key-renames")) {
            String[] p = rule.split("->", 2);
            if (p.length != 2) continue;
            String err = renameKey(p[0].trim(), p[1].trim());
            if (err != null) plugin.getLogger().fine("key-rename '" + rule + "' skipped: " + err);
        }
    }

    /** Registers any .litematic in plugins/DonutSMPWorld/schematics/ that isn't known yet. Returns how many were added. */
    public int registerFromFolder() {
        File dir = new File(plugin.getDataFolder(), "schematics");
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".litematic"));
        if (files == null) return 0;
        Set<String> known = new HashSet<>();
        for (SchematicMeta m : SCHEMATICS.values()) known.add(m.filename.toLowerCase());
        Arrays.sort(files);
        int added = 0;
        for (File f : files) {
            if (known.contains(f.getName().toLowerCase())) continue;
            try {
                NbtCompound root = readNbt(f, f.getName());
                int[] sz = null;
                NbtTag md = root.get("Metadata");
                if (md instanceof NbtCompound) {
                    NbtTag es = ((NbtCompound) md).get("EnclosingSize");
                    if (es instanceof NbtCompound) sz = readSize((NbtCompound) es);
                }
                if (sz == null) {
                    NbtTag regs = root.get("Regions");
                    if (regs instanceof NbtCompound) {
                        for (Map.Entry<String, NbtTag> e : ((NbtCompound) regs).entrySet()) {
                            NbtTag rs = ((NbtCompound) e.getValue()).get("Size");
                            if (rs instanceof NbtCompound) { sz = readSize((NbtCompound) rs); break; }
                        }
                    }
                }
                if (sz == null) { plugin.getLogger().warning("Could not read size of " + f.getName() + ", skipping"); continue; }
                String base = f.getName().substring(0, f.getName().length() - ".litematic".length());
                String key = base.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
                if (key.isEmpty()) key = "schem";
                String unique = key; int n = 2;
                while (SCHEMATICS.containsKey(unique)) unique = key + "_" + n++;
                SCHEMATICS.put(unique, new SchematicMeta(f.getName(), 2, base.replace('_', ' '), "main", sz[0], sz[1], sz[2], false));
                plugin.getLogger().info("Registered schematic '" + unique + "' from " + f.getName() + " (" + sz[0] + "x" + sz[1] + "x" + sz[2] + ")");
                added++;
            } catch (Exception e) {
                plugin.getLogger().warning("Could not register " + f.getName() + ": " + e.getMessage());
            }
        }
        return added;
    }

    private int[] readSize(NbtCompound c) {
        NbtTag x = c.get("x"), y = c.get("y"), z = c.get("z");
        if (!(x instanceof NbtInt) || !(y instanceof NbtInt) || !(z instanceof NbtInt)) return null;
        return new int[]{Math.abs(((NbtInt) x).value), Math.abs(((NbtInt) y).value), Math.abs(((NbtInt) z).value)};
    }

    private void pasteRegions(NbtCompound root, Location origin) {
        NbtCompound regions = (NbtCompound) root.get("Regions");
        if (regions == null) return;

        World world = origin.getWorld();

        for (Map.Entry<String, NbtTag> entry : regions.entrySet()) {
            NbtCompound region = (NbtCompound) entry.getValue();
            pasteRegion(region, origin, world);
        }
    }

    private void pasteRegion(NbtCompound region, Location origin, World world) {
        // Get size
        NbtCompound size = (NbtCompound) region.get("Size");
        if (size == null) return;
        int sizeX = Math.abs(((NbtInt) size.get("x")).value);
        int sizeY = Math.abs(((NbtInt) size.get("y")).value);
        int sizeZ = Math.abs(((NbtInt) size.get("z")).value);

        // Get palette
        NbtList palette = (NbtList) region.get("BlockStatePalette");
        if (palette == null) return;

        // Build lookups from palette: the block name, plus the full block state (facing, chest left/right, stairs shape...)
        String[] paletteBlocks = new String[palette.size()];
        BlockData[] paletteData = new BlockData[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            NbtCompound entry = (NbtCompound) palette.get(i);
            NbtString nameTag = (NbtString) entry.get("Name");
            paletteBlocks[i] = nameTag != null ? nameTag.value : "minecraft:air";
            paletteData[i] = buildBlockData(paletteBlocks[i], entry.get("Properties"));
        }

        // Get packed block states
        NbtLongArray blockStates = (NbtLongArray) region.get("BlockStates");
        if (blockStates == null) return;

        int paletteSize = palette.size();
        int bitsPerBlock = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1));
        long mask = (1L << bitsPerBlock) - 1L;

        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();

        int totalBlocks = sizeX * sizeY * sizeZ;
        // When true, everything inside the schematic's box that isn't part of the schematic is deleted (set to air),
        // so natural terrain/ores/water never poke through the build.
        boolean clear = plugin.getConfig().getBoolean("clear-terrain-inside", true);

        for (int i = 0; i < totalBlocks; i++) {
            int blockIndex = getBlockIndex(i, bitsPerBlock, blockStates.values);
            String blockName = (blockIndex < 0 || blockIndex >= paletteBlocks.length) ? "minecraft:air" : paletteBlocks[blockIndex];

            // structure_void means "leave whatever is already here"
            if (blockName.equals("minecraft:structure_void")) continue;

            boolean isAir = blockName.equals("minecraft:air") || blockName.equals("minecraft:void_air") || blockName.equals("minecraft:cave_air");
            if (isAir && !clear) continue;

            // Convert index to x,y,z
            int bx = i % sizeX;
            int bz = (i / sizeX) % sizeZ;
            int by = i / (sizeX * sizeZ);

            int worldX = ox + bx;
            int worldY = oy + by;
            int worldZ = oz + bz;

            // Skip if out of world bounds
            if (worldY < world.getMinHeight() || worldY >= world.getMaxHeight()) continue;

            try {
                BlockData data = isAir ? null : (blockIndex >= 0 && blockIndex < paletteData.length ? paletteData[blockIndex] : null);
                if (!isAir && data == null) {
                    // Block this server version doesn't know: clear the spot rather than leave terrain behind
                    if (!clear) continue;
                    isAir = true;
                }
                Block block = world.getBlockAt(worldX, worldY, worldZ);
                if (isAir) {
                    if (!block.getType().isAir()) block.setType(Material.AIR, false);
                } else {
                    block.setBlockData(data, false);
                }
            } catch (Exception ignored) {
                // Skip blocks that fail to place
            }
        }
        // Remember this box so no mobs spawn inside the schematic
        plugin.getNoSpawnZones().add(world, ox, oy, oz, ox + sizeX - 1, oy + sizeY - 1, oz + sizeZ - 1);
        pasteBoxes.add(new int[]{ox, oy, oz, ox + sizeX - 1, oy + sizeY - 1, oz + sizeZ - 1});
    }

    /**
     * Turns a palette entry (Name + Properties) into real BlockData so direction and shape are kept:
     * e.g. minecraft:chest[facing=north,type=left,waterlogged=false]. Falls back to the plain block if the
     * exact state isn't valid on this server version; returns null for blocks the server doesn't know.
     */
    private BlockData buildBlockData(String name, NbtTag propsTag) {
        String id = name.contains(":") ? name : "minecraft:" + name;
        StringBuilder sb = new StringBuilder(id);
        if (propsTag instanceof NbtCompound && !((NbtCompound) propsTag).isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Map.Entry<String, NbtTag> e : ((NbtCompound) propsTag).entrySet()) {
                if (!(e.getValue() instanceof NbtString)) continue;
                if (!first) sb.append(',');
                sb.append(e.getKey()).append('=').append(((NbtString) e.getValue()).value);
                first = false;
            }
            sb.append(']');
        }
        try {
            return org.bukkit.Bukkit.createBlockData(sb.toString());
        } catch (Exception ex) {
            try {
                return org.bukkit.Bukkit.createBlockData(id);   // right block, default state
            } catch (Exception ex2) {
                Material m = parseMaterial(name);
                if (m != null && m.isBlock()) {
                    try { return m.createBlockData(); } catch (Exception ignored) { }
                }
                return null;
            }
        }
    }

    /** Litematica packs entries tightly across long boundaries (no padding), for any bit width. */
    private int getBlockIndex(int blockPos, int bitsPerBlock, long[] states) {
        long bitStart = (long) blockPos * bitsPerBlock;
        int word = (int) (bitStart >>> 6);
        int offset = (int) (bitStart & 63L);
        if (word >= states.length) return 0;
        long mask = (1L << bitsPerBlock) - 1L;
        long value = states[word] >>> offset;
        if (offset + bitsPerBlock > 64 && word + 1 < states.length) {
            value |= states[word + 1] << (64 - offset);
        }
        return (int) (value & mask);
    }

    private Material parseMaterial(String namespacedId) {
        // Strip namespace
        String id = namespacedId.contains(":") ? namespacedId.split(":")[1] : namespacedId;
        // Strip block state properties
        if (id.contains("[")) id = id.substring(0, id.indexOf("["));
        id = id.toUpperCase();

        try {
            return Material.valueOf(id);
        } catch (IllegalArgumentException e) {
            // Try some common mappings
            return BLOCK_MAPPINGS.getOrDefault(id, null);
        }
    }

    // Common block name remappings
    private static final Map<String, Material> BLOCK_MAPPINGS = new HashMap<>();
    static {
        BLOCK_MAPPINGS.put("GRASS_BLOCK", Material.GRASS_BLOCK);
        BLOCK_MAPPINGS.put("STONE_SLAB", Material.STONE_SLAB);
        BLOCK_MAPPINGS.put("WOODEN_SLAB", Material.OAK_SLAB);
        BLOCK_MAPPINGS.put("STEP", Material.STONE_SLAB);
        BLOCK_MAPPINGS.put("DOUBLE_STEP", Material.SMOOTH_STONE);
        BLOCK_MAPPINGS.put("LOG", Material.OAK_LOG);
        BLOCK_MAPPINGS.put("LOG2", Material.OAK_LOG);
        BLOCK_MAPPINGS.put("LEAVES", Material.OAK_LEAVES);
        BLOCK_MAPPINGS.put("LEAVES2", Material.OAK_LEAVES);
        BLOCK_MAPPINGS.put("SAPLING", Material.OAK_SAPLING);
        BLOCK_MAPPINGS.put("BED", Material.RED_BED);
        BLOCK_MAPPINGS.put("YELLOW_FLOWER", Material.DANDELION);
        BLOCK_MAPPINGS.put("RED_FLOWER", Material.POPPY);
        BLOCK_MAPPINGS.put("DOUBLE_PLANT", Material.SUNFLOWER);
        BLOCK_MAPPINGS.put("TALLGRASS", Material.SHORT_GRASS);
        BLOCK_MAPPINGS.put("DEADBUSH", Material.DEAD_BUSH);
        BLOCK_MAPPINGS.put("WOOL", Material.WHITE_WOOL);
        BLOCK_MAPPINGS.put("STAINED_GLASS", Material.WHITE_STAINED_GLASS);
        BLOCK_MAPPINGS.put("STAINED_GLASS_PANE", Material.WHITE_STAINED_GLASS_PANE);
        BLOCK_MAPPINGS.put("STAINED_HARDENED_CLAY", Material.WHITE_TERRACOTTA);
        BLOCK_MAPPINGS.put("HARDENED_CLAY", Material.TERRACOTTA);
        BLOCK_MAPPINGS.put("QUARTZ_ORE", Material.NETHER_QUARTZ_ORE);
        BLOCK_MAPPINGS.put("BRICK_BLOCK", Material.BRICKS);
        BLOCK_MAPPINGS.put("STONE_BRICK", Material.STONE_BRICKS);
        BLOCK_MAPPINGS.put("NETHER_BRICK", Material.NETHER_BRICKS);
        BLOCK_MAPPINGS.put("SNOW_LAYER", Material.SNOW);
        BLOCK_MAPPINGS.put("WATER", Material.WATER);
        BLOCK_MAPPINGS.put("LAVA", Material.LAVA);
        BLOCK_MAPPINGS.put("FLOWING_WATER", Material.WATER);
        BLOCK_MAPPINGS.put("FLOWING_LAVA", Material.LAVA);
        BLOCK_MAPPINGS.put("PORTAL", Material.NETHER_PORTAL);
        BLOCK_MAPPINGS.put("END_PORTAL", Material.END_PORTAL);
        BLOCK_MAPPINGS.put("FENCE", Material.OAK_FENCE);
        BLOCK_MAPPINGS.put("FENCE_GATE", Material.OAK_FENCE_GATE);
        BLOCK_MAPPINGS.put("WOODEN_DOOR", Material.OAK_DOOR);
        BLOCK_MAPPINGS.put("TRAPDOOR", Material.OAK_TRAPDOOR);
        BLOCK_MAPPINGS.put("SIGN", Material.OAK_SIGN);
        BLOCK_MAPPINGS.put("WALL_SIGN", Material.OAK_WALL_SIGN);
        BLOCK_MAPPINGS.put("STANDING_SIGN", Material.OAK_SIGN);
        BLOCK_MAPPINGS.put("SKULL", Material.SKELETON_SKULL);
        BLOCK_MAPPINGS.put("FLOWER_POT", Material.FLOWER_POT);
        BLOCK_MAPPINGS.put("CAULDRON", Material.CAULDRON);
        BLOCK_MAPPINGS.put("BREWING_STAND", Material.BREWING_STAND);
        BLOCK_MAPPINGS.put("ENCHANTING_TABLE", Material.ENCHANTING_TABLE);
        BLOCK_MAPPINGS.put("ENDER_CHEST", Material.ENDER_CHEST);
        BLOCK_MAPPINGS.put("COMMAND_BLOCK", Material.COMMAND_BLOCK);
        BLOCK_MAPPINGS.put("BARRIER", Material.BARRIER);
        BLOCK_MAPPINGS.put("STRUCTURE_BLOCK", Material.STRUCTURE_BLOCK);
        BLOCK_MAPPINGS.put("GLASS_PANE", Material.GLASS_PANE);
        BLOCK_MAPPINGS.put("IRON_FENCE", Material.IRON_BARS);
        BLOCK_MAPPINGS.put("NETHER_FENCE", Material.NETHER_BRICK_FENCE);
        BLOCK_MAPPINGS.put("COBBLE_WALL", Material.COBBLESTONE_WALL);
        BLOCK_MAPPINGS.put("STONE_WALL", Material.COBBLESTONE_WALL);
        BLOCK_MAPPINGS.put("CARPET", Material.WHITE_CARPET);
        BLOCK_MAPPINGS.put("GLASS", Material.GLASS);
        BLOCK_MAPPINGS.put("SANDSTONE", Material.SANDSTONE);
    }

    // ===== Minimal NBT Parser =====

    private NbtCompound readNbt(File file, String filename) throws IOException {
        InputStream src = file.exists() ? new FileInputStream(file) : plugin.getResource("schematics/" + filename);
        if (src == null) throw new FileNotFoundException(filename);
        try (InputStream raw = src;
             GZIPInputStream gz = new GZIPInputStream(raw);
             DataInputStream dis = new DataInputStream(gz)) {
            byte tagType = dis.readByte();
            String name = readString(dis);
            return (NbtCompound) readTag(dis, tagType);
        }
    }

    private NbtTag readTag(DataInputStream dis, int type) throws IOException {
        switch (type) {
            case 1: return new NbtByte(dis.readByte());
            case 2: return new NbtShort(dis.readShort());
            case 3: return new NbtInt(dis.readInt());
            case 4: return new NbtLong(dis.readLong());
            case 5: return new NbtFloat(dis.readFloat());
            case 6: return new NbtDouble(dis.readDouble());
            case 7: { // byte array
                int len = dis.readInt();
                byte[] bytes = new byte[len];
                dis.readFully(bytes);
                return new NbtByteArray(bytes);
            }
            case 8: return new NbtString(readString(dis));
            case 9: { // list
                int elemType = dis.readByte() & 0xFF;
                int len = dis.readInt();
                NbtList list = new NbtList();
                for (int i = 0; i < len; i++) list.add(readTag(dis, elemType));
                return list;
            }
            case 10: { // compound
                NbtCompound compound = new NbtCompound();
                while (true) {
                    int childType = dis.readByte() & 0xFF;
                    if (childType == 0) break; // TAG_End
                    String childName = readString(dis);
                    compound.put(childName, readTag(dis, childType));
                }
                return compound;
            }
            case 11: { // int array
                int len = dis.readInt();
                int[] ints = new int[len];
                for (int i = 0; i < len; i++) ints[i] = dis.readInt();
                return new NbtIntArray(ints);
            }
            case 12: { // long array
                int len = dis.readInt();
                long[] longs = new long[len];
                for (int i = 0; i < len; i++) longs[i] = dis.readLong();
                return new NbtLongArray(longs);
            }
            default: throw new IOException("Unknown NBT tag type: " + type);
        }
    }

    private String readString(DataInputStream dis) throws IOException {
        int len = dis.readUnsignedShort();
        byte[] bytes = new byte[len];
        dis.readFully(bytes);
        return new String(bytes, "UTF-8");
    }

    // ===== NBT Tag Types =====
    interface NbtTag {}
    static class NbtByte    implements NbtTag { byte value;   NbtByte(byte v)   { value = v; } }
    static class NbtShort   implements NbtTag { short value;  NbtShort(short v) { value = v; } }
    static class NbtInt     implements NbtTag { int value;    NbtInt(int v)     { value = v; } }
    static class NbtLong    implements NbtTag { long value;   NbtLong(long v)   { value = v; } }
    static class NbtFloat   implements NbtTag { float value;  NbtFloat(float v) { value = v; } }
    static class NbtDouble  implements NbtTag { double value; NbtDouble(double v){ value = v; } }
    static class NbtString  implements NbtTag { String value; NbtString(String v){ value = v; } }
    static class NbtByteArray implements NbtTag { byte[] values; NbtByteArray(byte[] v){ values = v; } }
    static class NbtIntArray  implements NbtTag { int[] values;  NbtIntArray(int[] v)  { values = v; } }
    static class NbtLongArray implements NbtTag { long[] values; NbtLongArray(long[] v){ values = v; } }
    static class NbtList    extends ArrayList<NbtTag> implements NbtTag {}
    static class NbtCompound extends LinkedHashMap<String, NbtTag> implements NbtTag {}

    // ===== Schematic Metadata =====
    public static class SchematicMeta {
        public final String filename;
        public final int difficulty; // 1=easy, 2=medium, 3=hard (harder = further/deeper)
        public final String displayName;
        public final String lootTier;
        public final int sizeX, sizeY, sizeZ;
        public final boolean extraHidden; // DrDonut and Greek get extra concealment

        public SchematicMeta(String filename, int difficulty, String displayName,
                             String lootTier, int sizeX, int sizeY, int sizeZ, boolean extraHidden) {
            this.filename = filename;
            this.difficulty = difficulty;
            this.displayName = displayName;
            this.lootTier = lootTier;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.extraHidden = extraHidden;
        }
    }
}
