package com.donutsmp.plugin;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.Material;

import java.util.*;
import java.util.Collections;

/**
 * Generates DonutSMP-style underground bases:
 * - Media base (huge, flashy, lots of loot)
 * - Baltop base (rich hoarder, diamond blocks everywhere)
 * - Main base (normal player feeling)
 * - Nomad stash (tiny hidden room)
 * - Trap base (looks great, fully dangerous)
 */
public class BaseGenerator {

    private final DonutSMPPlugin plugin;
    private final ActivityTracker activityTracker;
    private final Random random = new Random();
    private int generatedCount = 0;

    // Fake player names for signs/books
    private static final String[] FAKE_NAMES = {
        "xX_D0nut_Xx", "CrafterPro99", "ShadowBlade_", "NightWolf2k",
        "DiamondKing", "StealthMode", "BaseBuilder", "VaultKeeper",
        "GrindMaster", "OfflineRaider", "TunnelRat", "DeepDiver"
    };

    private static final String[] FAKE_COORDS = {
        "2847, -3921", "-1204, 884", "5003, 2211", "-3847, -1055",
        "1337, 4200", "-2048, -2048", "6660, 1234", "-500, 9001"
    };

    public BaseGenerator(DonutSMPPlugin plugin, ActivityTracker activityTracker) {
        this.plugin = plugin;
        this.activityTracker = activityTracker;
    }

    public int generateAllBases() {
        World world = plugin.getServer().getWorlds().get(0);
        double mult = Math.max(0.05, plugin.getConfig().getDouble("base-finding.distance-multiplier", 0.35));
        int border = Math.max(1000, (int) (plugin.getConfig().getInt("world-border", 10000) * mult));
        int baseCount = plugin.getConfig().getInt("base-count", 8);
        int depth = plugin.getConfig().getInt("base-depth", -30)
                  + plugin.getConfig().getInt("base-finding.raise-bases", 25);
        depth = Math.max(depth, world.getMinHeight() + 6);

        List<int[]> positions = generateSpreadPositions(baseCount, border);
        generatedCount = 0;

        for (int i = 0; i < positions.size(); i++) {
            int[] pos = positions.get(i);
            int x = pos[0];
            int z = pos[1];
            Location loc = new Location(world, x, depth, z);

            // Ensure chunk is loaded
            world.loadChunk(x >> 4, z >> 4);

            String type = pickBaseType(i, baseCount);
            generateBase(loc, type);

            // Register as activity zone so geodes spawn nearby
            activityTracker.addActivityZone(new Location(world, x, 64, z));
            // Register with StaffWatcher so near-base logins are flagged
            if (plugin.getStaffWatcher() != null)
                plugin.getStaffWatcher().registerBase(new Location(world, x, depth, z));
            generatedCount++;

            plugin.getLogger().info("Generated " + type + " base at " + x + ", " + depth + ", " + z);
        }

        return generatedCount;
    }

    private String pickBaseType(int index, int total) {
        // Ensure at least one of each important type
        if (index == 0) return "media";
        if (index == 1) return "baltop";
        if (index == 2) return "trap";
        // Rest are mix of main and nomad
        return random.nextBoolean() ? "main" : "nomad";
    }

    // Footprint of each base type relative to its origin: {x0, x1, y0, y1, z0, z1}.
    // Includes the tunnels / shafts each builder carves, so spacing and /removebase see the whole thing.
    private static final int[] BOX_MEDIA  = {-15, 15,  0,  8, -15, 15};
    private static final int[] BOX_BALTOP = { -7,  7,  0,  6, -25, 10};
    private static final int[] BOX_MAIN   = { -6,  6, -14, 5,  -8,  8};
    private static final int[] BOX_NOMAD  = { -2,  2,  0, 20,  -3,  2};
    private static final int[] BOX_TRAP   = { -8,  8, -2,  7, -25, 15};

    private static int[] boxFor(String type) {
        switch (type.toLowerCase()) {
            case "media":  return BOX_MEDIA;
            case "baltop": return BOX_BALTOP;
            case "trap":   return BOX_TRAP;
            case "nomad":  return BOX_NOMAD;
            default:       return BOX_MAIN;
        }
    }

    public void generateBase(Location origin, String type) {
        // Remember it right away (not inside the scheduled task) so the next base picked in the same
        // batch already sees this one for spacing, and so /removebase can find it later.
        PlacementRegistry reg = plugin.getPlacementRegistry();
        if (reg != null) reg.registerAround(PlacementRegistry.BASE, type, origin, boxFor(type));
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            switch (type.toLowerCase()) {
                case "media": buildMediaBase(origin); break;
                case "baltop": buildBaltopBase(origin); break;
                case "trap": buildTrapBase(origin); break;
                case "nomad": buildNomadStash(origin); break;
                default: buildMainBase(origin); break;
            }
        });
    }

    // ============================================================
    // MEDIA BASE - Big, flashy, looks like a YouTuber lives here
    // ============================================================
    private void buildMediaBase(Location origin) {
        currentBaseType = "media";
        World w = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        String name = FAKE_NAMES[random.nextInt(FAKE_NAMES.length)];

        // Main hall - 20x8x30
        fillBox(w, ox - 10, oy, oz - 15, ox + 10, oy + 8, oz + 15, Material.AIR);
        // Walls - polished deepslate with accents
        outlineBox(w, ox - 10, oy, oz - 15, ox + 10, oy + 8, oz + 15, Material.POLISHED_DEEPSLATE);
        // Floor - smooth stone
        fillLayer(w, ox - 10, oy, oz - 15, ox + 10, oz + 15, Material.SMOOTH_STONE);
        // Ceiling with glowstone lighting
        fillLayer(w, ox - 10, oy + 8, oz - 15, ox + 10, oz + 15, Material.POLISHED_DEEPSLATE);
        placeBlock(w, ox, oy + 8, oz, Material.GLOWSTONE);
        placeBlock(w, ox - 5, oy + 8, oz - 7, Material.GLOWSTONE);
        placeBlock(w, ox + 5, oy + 8, oz + 7, Material.GLOWSTONE);
        placeBlock(w, ox - 5, oy + 8, oz + 7, Material.GLOWSTONE);
        placeBlock(w, ox + 5, oy + 8, oz - 7, Material.GLOWSTONE);

        // Entrance tunnel from above
        for (int y = oy + 9; y < oy + 45; y++) {
            placeBlock(w, ox, y, oz - 14, Material.AIR);
            placeBlock(w, ox, y, oz - 13, Material.AIR);
        }

        // Storage wing - left side
        fillBox(w, ox - 10, oy, oz - 5, ox - 15, oy + 5, oz + 5, Material.AIR);
        outlineBox(w, ox - 10, oy, oz - 5, ox - 15, oy + 5, oz + 5, Material.DEEPSLATE_BRICKS);
        placeChestWithLoot(w, ox - 12, oy + 1, oz - 3, "media");
        placeChestWithLoot(w, ox - 12, oy + 1, oz, "media");
        placeChestWithLoot(w, ox - 12, oy + 1, oz + 3, "main");
        placeChestWithLoot(w, ox - 13, oy + 1, oz - 3, "main");
        placeChestWithLoot(w, ox - 13, oy + 1, oz, "baltop");
        // Fake "secret" shulker display
        placeBlock(w, ox - 11, oy + 1, oz, Material.PURPLE_SHULKER_BOX);

        // Enchanting room - right side
        fillBox(w, ox + 10, oy, oz - 5, ox + 15, oy + 5, oz + 5, Material.AIR);
        outlineBox(w, ox + 10, oy, oz - 5, ox + 15, oy + 5, oz + 5, Material.DEEPSLATE_BRICKS);
        placeBlock(w, ox + 12, oy + 1, oz, Material.ENCHANTING_TABLE);
        placeBlock(w, ox + 11, oy + 1, oz - 1, Material.BOOKSHELF);
        placeBlock(w, ox + 11, oy + 1, oz + 1, Material.BOOKSHELF);
        placeBlock(w, ox + 13, oy + 1, oz - 1, Material.BOOKSHELF);
        placeBlock(w, ox + 13, oy + 1, oz + 1, Material.BOOKSHELF);
        placeBlock(w, ox + 11, oy + 1, oz, Material.BOOKSHELF);
        placeBlock(w, ox + 13, oy + 1, oz, Material.BOOKSHELF);

        // Living quarters
        placeBlock(w, ox - 3, oy + 1, oz + 10, Material.RED_BED);
        placeBlock(w, ox - 2, oy + 1, oz + 10, Material.RED_BED);
        placeBlock(w, ox + 3, oy + 1, oz + 10, Material.CRAFTING_TABLE);
        placeBlock(w, ox + 2, oy + 1, oz + 10, Material.FURNACE);
        placeBlock(w, ox + 1, oy + 1, oz + 10, Material.FURNACE);
        placeBlock(w, ox - 1, oy + 1, oz + 10, Material.ANVIL);

        // Signs
        placeSign(w, ox, oy + 2, oz - 14, name + "'s Base", "KEEP OUT", "TRESPASSERS", "WILL BE KILLED");
        placeSign(w, ox - 9, oy + 2, oz, "STORAGE", "-> LEFT", "", "");
        placeSign(w, ox + 9, oy + 2, oz, "ENCHANTING", "-> RIGHT", "", "");

        // Fake journal book in a lectern
        placeBook(w, ox, oy + 1, oz + 5, name,
            "Day 1: Finally found a good spot. Going deep underground so no one finds me.",
            "Day 15: Base is looking good. Got 3 stashes spread across the map just in case.",
            "Day 30: Someone found my decoy base at " + FAKE_COORDS[random.nextInt(FAKE_COORDS.length)] + ". Good thing it was a trap lol",
            "Day 45: Reached baltop. Not storing everything here obviously."
        );

        // Secret room hint (leads nowhere useful)
        placeSign(w, ox, oy + 1, oz - 5, "Real stash is at", FAKE_COORDS[random.nextInt(FAKE_COORDS.length)], "(jk)", "");
    }

    // ============================================================
    // BALTOP BASE - Rich hoarder, diamond/netherite everywhere
    // ============================================================
    private void buildBaltopBase(Location origin) {
        currentBaseType = "baltop";
        World w = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        String name = FAKE_NAMES[random.nextInt(FAKE_NAMES.length)];

        // Main vault room - 15x6x20
        fillBox(w, ox - 7, oy, oz - 10, ox + 7, oy + 6, oz + 10, Material.AIR);
        outlineBox(w, ox - 7, oy, oz - 10, ox + 7, oy + 6, oz + 10, Material.OBSIDIAN);
        fillLayer(w, ox - 7, oy, oz - 10, ox + 7, oz + 10, Material.DIAMOND_BLOCK);
        // Replace some diamond with netherite for authenticity
        for (int i = 0; i < 5; i++) {
            int rx = ox - 6 + random.nextInt(12);
            int rz = oz - 9 + random.nextInt(18);
            placeBlock(w, rx, oy, rz, Material.NETHERITE_BLOCK);
        }

        // Ceiling - obsidian with crying obsidian patterns
        fillLayer(w, ox - 7, oy + 6, oz - 10, ox + 7, oz + 10, Material.OBSIDIAN);
        for (int i = 0; i < 8; i++) {
            int rx = ox - 6 + random.nextInt(12);
            int rz = oz - 9 + random.nextInt(18);
            placeBlock(w, rx, oy + 6, rz, Material.CRYING_OBSIDIAN);
        }

        // Wall of chests
        for (int z2 = oz - 8; z2 <= oz + 8; z2 += 2) {
            placeChestWithLoot(w, ox - 6, oy + 1, z2, "baltop");
            placeChestWithLoot(w, ox + 6, oy + 1, z2, "baltop");
        }
        for (int z2 = oz - 8; z2 <= oz + 8; z2 += 2) {
            placeChestWithLoot(w, ox - 6, oy + 2, z2, "main");
            placeChestWithLoot(w, ox + 6, oy + 2, z2, "main");
        }

        // Center display - beacon on diamond blocks
        fillBox(w, ox - 1, oy + 1, oz - 1, ox + 1, oy + 3, oz + 1, Material.DIAMOND_BLOCK);
        placeBlock(w, ox, oy + 4, oz, Material.BEACON);

        // Entrance - iron door with combination hint
        placeBlock(w, ox, oy + 1, oz - 10, Material.IRON_DOOR);
        placeBlock(w, ox, oy + 2, oz - 10, Material.IRON_DOOR);
        placeSign(w, ox + 1, oy + 1, oz - 10, "PASSWORD:", "wrong lol", "nice try", ":)");

        // Tunnel connects back
        for (int z2 = oz - 10; z2 > oz - 25; z2--) {
            placeBlock(w, ox, oy + 1, z2, Material.AIR);
            placeBlock(w, ox, oy + 2, z2, Material.AIR);
        }

        // Fake "real vault" sign pointing wrong way
        placeSign(w, ox, oy + 2, oz + 9, "Nothing here.", "", "", "");
        placeSign(w, ox, oy + 1, oz + 5, name, "BALTOP #1", "Season 3", "");
    }

    // ============================================================
    // MAIN BASE - Normal player, been playing a few weeks
    // ============================================================
    private void buildMainBase(Location origin) {
        currentBaseType = "main";
        World w = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        String name = FAKE_NAMES[random.nextInt(FAKE_NAMES.length)];

        // Main room - 12x5x16, stone brick
        fillBox(w, ox - 6, oy, oz - 8, ox + 6, oy + 5, oz + 8, Material.AIR);
        outlineBox(w, ox - 6, oy, oz - 8, ox + 6, oy + 5, oz + 8, Material.STONE_BRICKS);
        // Mix in cracked/mossy for lived-in feel
        for (int i = 0; i < 20; i++) {
            int rx = ox - 5 + random.nextInt(10);
            int ry = oy + random.nextInt(5);
            int rz = oz - 7 + random.nextInt(14);
            placeBlock(w, rx, ry, rz,
                random.nextBoolean() ? Material.CRACKED_STONE_BRICKS : Material.MOSSY_STONE_BRICKS);
        }

        // Floor - smooth stone
        fillLayer(w, ox - 6, oy, oz - 8, ox + 6, oz + 8, Material.SMOOTH_STONE);

        // Lighting
        for (int i = -6; i <= 6; i += 4) {
            placeBlock(w, ox + i, oy + 4, oz - 6, Material.TORCH);
            placeBlock(w, ox + i, oy + 4, oz + 6, Material.TORCH);
        }

        // Living area
        placeBlock(w, ox - 4, oy + 1, oz + 5, Material.WHITE_BED);
        placeBlock(w, ox - 3, oy + 1, oz + 5, Material.WHITE_BED);
        placeBlock(w, ox + 4, oy + 1, oz + 5, Material.CRAFTING_TABLE);
        placeBlock(w, ox + 3, oy + 1, oz + 5, Material.FURNACE);
        placeBlock(w, ox + 2, oy + 1, oz + 5, Material.FURNACE);

        // Storage area
        placeChestWithLoot(w, ox - 4, oy + 1, oz, "main");
        placeChestWithLoot(w, ox - 3, oy + 1, oz, "main");
        placeChestWithLoot(w, ox - 2, oy + 1, oz, "nomad");
        placeChestWithLoot(w, ox - 4, oy + 1, oz + 1, "nomad");

        // Enchanting nook
        placeBlock(w, ox + 4, oy + 1, oz - 3, Material.ENCHANTING_TABLE);
        placeBlock(w, ox + 3, oy + 1, oz - 3, Material.BOOKSHELF);
        placeBlock(w, ox + 5, oy + 1, oz - 3, Material.BOOKSHELF);
        placeBlock(w, ox + 4, oy + 1, oz - 2, Material.BOOKSHELF);
        placeBlock(w, ox + 4, oy + 1, oz - 4, Material.BOOKSHELF);

        // Fake mine shaft going deeper (dead end)
        for (int y = oy - 1; y > oy - 15; y--) {
            placeBlock(w, ox, y, oz - 7, Material.AIR);
            placeBlock(w, ox + 1, y, oz - 7, Material.AIR);
        }
        // Dead end chest with junk
        placeChestWithLoot(w, ox, oy - 14, oz - 7, "nomad");
        placeSign(w, ox + 1, oy - 14, oz - 7, "old stash", "probably safe", "probably...", "");

        // Entrance
        placeSign(w, ox, oy + 2, oz - 8, name + "'s", "Main Base", "S2", "");
    }

    // ============================================================
    // NOMAD STASH - Tiny hidden room, looks like someone passing through
    // ============================================================
    private void buildNomadStash(Location origin) {
        currentBaseType = "nomad";
        World w = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();

        // Tiny room 4x3x4 - looks like expanded natural cave
        fillBox(w, ox - 2, oy, oz - 2, ox + 2, oy + 3, oz + 2, Material.AIR);
        // Don't outline - leave natural stone walls
        // Just a floor
        fillLayer(w, ox - 2, oy, oz - 2, ox + 2, oz + 2, Material.COBBLESTONE);

        // Single torch
        placeBlock(w, ox, oy + 2, oz, Material.TORCH);

        // Chest hidden in corner
        placeChestWithLoot(w, ox - 1, oy + 1, oz - 1, "nomad");
        // Cover chest with trapdoor (disguise)
        placeBlock(w, ox - 1, oy + 2, oz - 1, Material.OAK_TRAPDOOR);

        // Bed - person slept here
        placeBlock(w, ox + 1, oy + 1, oz, Material.GREEN_BED);
        placeBlock(w, ox + 1, oy + 1, oz + 1, Material.GREEN_BED);

        // Entrance - disguised with gravel (looks natural)
        for (int y = oy + 1; y < oy + 20; y++) {
            placeBlock(w, ox, y, oz - 3, Material.AIR);
        }
        // Plug entrance with gravel (falls, looks natural)
        placeBlock(w, ox, oy + 1, oz - 2, Material.GRAVEL);
        placeBlock(w, ox, oy + 2, oz - 2, Material.GRAVEL);
    }

    // ============================================================
    // TRAP BASE - Looks amazing, fully dangerous inside
    // ============================================================
    private void buildTrapBase(Location origin) {
        currentBaseType = "trap";
        World w = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();

        // Fancy entrance room - polished blackstone
        fillBox(w, ox - 8, oy, oz - 10, ox + 8, oy + 7, oz + 10, Material.AIR);
        outlineBox(w, ox - 8, oy, oz - 10, ox + 8, oy + 7, oz + 10, Material.POLISHED_BLACKSTONE_BRICKS);
        fillLayer(w, ox - 8, oy, oz - 10, ox + 8, oz + 10, Material.POLISHED_BLACKSTONE);

        // Fancy lighting - soul lanterns
        placeBlock(w, ox - 4, oy + 6, oz - 5, Material.SOUL_LANTERN);
        placeBlock(w, ox + 4, oy + 6, oz - 5, Material.SOUL_LANTERN);
        placeBlock(w, ox - 4, oy + 6, oz + 5, Material.SOUL_LANTERN);
        placeBlock(w, ox + 4, oy + 6, oz + 5, Material.SOUL_LANTERN);

        // Display chests - visible, tempting, TRAPPED
        // Chest on pressure plate = TNT below
        for (int i = -6; i <= 6; i += 3) {
            placeBlock(w, ox + i, oy, oz, Material.TNT);  // Under floor
            placeBlock(w, ox + i, oy + 1, oz, Material.STONE_PRESSURE_PLATE);
            // Chest right next to pressure plate
            placeChestWithLoot(w, ox + i + 1, oy + 1, oz, "main");
        }

        // "Vault" room at the back - more elaborate trap
        fillBox(w, ox - 4, oy, oz + 5, ox + 4, oy + 5, oz + 15, Material.AIR);
        outlineBox(w, ox - 4, oy, oz + 5, ox + 4, oy + 5, oz + 15, Material.GILDED_BLACKSTONE);

        // Floor of vault is fake - 2 layers of wood over lava pit
        fillLayer(w, ox - 4, oy, oz + 5, ox + 4, oz + 15, Material.OAK_PLANKS);
        // Lava pit 2 blocks below
        for (int x2 = ox - 3; x2 <= ox + 3; x2++) {
            for (int z2 = oz + 6; z2 <= oz + 14; z2++) {
                placeBlock(w, x2, oy - 2, z2, Material.LAVA);
            }
        }

        // Vault chest on the fake floor (walking near = floor breaks)
        placeChestWithLoot(w, ox, oy + 1, oz + 10, "baltop"); // good loot to tempt
        placeBlock(w, ox - 1, oy + 1, oz + 10, Material.DIAMOND_BLOCK); // extra tempting
        placeBlock(w, ox + 1, oy + 1, oz + 10, Material.DIAMOND_BLOCK);

        // Signs to lure players in
        placeSign(w, ox, oy + 2, oz - 9, "SAFE BASE", "Come in :)", "No traps", "I promise");
        placeSign(w, ox, oy + 2, oz + 4, "VAULT", "Best loot", "on the server", "just walk in");
        placeSign(w, ox, oy + 2, oz + 9, "almost there...", "", "", "");

        // Escape tunnel for the "owner" (behind a hidden piston - simplified as just a tunnel)
        for (int z2 = oz - 10; z2 > oz - 25; z2--) {
            placeBlock(w, ox - 7, oy + 1, z2, Material.AIR);
            placeBlock(w, ox - 7, oy + 2, z2, Material.AIR);
        }
    }

    // ============================================================
    // Helper methods
    // ============================================================

    private void fillBox(World w, int x1, int y1, int z1, int x2, int y2, int z2, Material mat) {
        for (int x = Math.min(x1,x2); x <= Math.max(x1,x2); x++)
            for (int y = Math.min(y1,y2); y <= Math.max(y1,y2); y++)
                for (int z = Math.min(z1,z2); z <= Math.max(z1,z2); z++)
                    placeBlock(w, x, y, z, mat);
    }

    private void outlineBox(World w, int x1, int y1, int z1, int x2, int y2, int z2, Material mat) {
        for (int x = Math.min(x1,x2); x <= Math.max(x1,x2); x++)
            for (int y = Math.min(y1,y2); y <= Math.max(y1,y2); y++)
                for (int z = Math.min(z1,z2); z <= Math.max(z1,z2); z++) {
                    boolean edge = (x == x1 || x == x2 || y == y1 || y == y2 || z == z1 || z == z2);
                    if (edge) placeBlock(w, x, y, z, mat);
                }
    }

    private void fillLayer(World w, int x1, int y, int z1, int x2, int z2, Material mat) {
        for (int x = Math.min(x1,x2); x <= Math.max(x1,x2); x++)
            for (int z = Math.min(z1,z2); z <= Math.max(z1,z2); z++)
                placeBlock(w, x, y, z, mat);
    }

    private void placeBlock(World w, int x, int y, int z, Material mat) {
        Block block = w.getBlockAt(x, y, z);
        block.setType(mat, false);
    }

    // ----------------------------------------------------------------
    // Loot multipliers per base type:
    //   nomad  → 1.0x  (small hidden room, single chest)
    //   main   → 1.5x  (normal player base)
    //   media  → 2.5x  (big YouTuber hall)
    //   baltop → 3.0x  (hoarder vault, diamond-block floor)
    //   trap   → 2.0x  (tempting bait loot)
    // ----------------------------------------------------------------
    private double getLootMultiplier(String baseType) {
        switch (baseType.toLowerCase()) {
            case "baltop": return 3.0;
            case "media":  return 2.5;
            case "regear": return 2.5; // regear bases always drop full netherite kits
            case "trap":   return 2.0;
            case "main":   return 1.5;
            default:       return 1.0;
        }
    }

    // Current base type being generated — set at the top of each buildXxxBase()
    private String currentBaseType = "main";

    private void placeChestWithLoot(World w, int x, int y, int z, String tier) {
        placeBlock(w, x, y, z, Material.CHEST);
        Block b = w.getBlockAt(x, y, z);
        if (b.getState() instanceof Chest) {
            Chest chest = (Chest) b.getState();
            double mult = getLootMultiplier(currentBaseType);
            List<ItemStack> loot = LootTable.getLoot(tier, random, mult);

            // Fill ALL 27 slots — shuffle then pack sequentially
            Collections.shuffle(loot, random);
            int slot = 0;
            for (ItemStack item : loot) {
                if (slot >= 27) break;
                chest.getInventory().setItem(slot++, item);
            }
            // Loop loot to stuff any remaining empty slots
            if (slot < 27 && !loot.isEmpty()) {
                for (int i = 0; slot < 27; i++, slot++) {
                    ItemStack filler = loot.get(i % loot.size()).clone();
                    if (chest.getInventory().getItem(slot) == null)
                        chest.getInventory().setItem(slot, filler);
                }
            }
            chest.update();
        }
    }

    private void placeSign(World w, int x, int y, int z, String line1, String line2, String line3, String line4) {
        placeBlock(w, x, y, z, Material.OAK_SIGN);
        Block b = w.getBlockAt(x, y, z);
        if (b.getState() instanceof Sign) {
            Sign sign = (Sign) b.getState();
            sign.setLine(0, line1);
            sign.setLine(1, line2);
            sign.setLine(2, line3);
            sign.setLine(3, line4);
            sign.update();
        }
    }

    private void placeBook(World w, int x, int y, int z, String author, String... pages) {
        placeBlock(w, x, y, z, Material.LECTERN);
        // Book in lectern requires extra logic, place written book in nearby chest instead
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta != null) {
            meta.setTitle("Journal");
            meta.setAuthor(author);
            for (String page : pages) meta.addPage(page);
            book.setItemMeta(meta);
        }
        // Find nearby chest and put book in it
        Block nearby = w.getBlockAt(x + 1, y, z);
        if (nearby.getState() instanceof Chest) {
            ((Chest) nearby.getState()).getInventory().addItem(book);
        }
    }

    private List<int[]> generateSpreadPositions(int count, int border) {
        List<int[]> positions = new ArrayList<>();
        World world = plugin.getServer().getWorlds().get(0);
        PlacementRegistry reg = plugin.getPlacementRegistry();
        int gap = reg != null ? reg.configuredGap() : 0;
        int minDist = border / (count + 1);
        int attempts = 0;
        while (positions.size() < count && attempts < 1000) {
            int x = (random.nextInt(border * 2) - border);
            int z = (random.nextInt(border * 2) - border);
            // Keep away from spawn
            if (Math.abs(x) < 500 && Math.abs(z) < 500) { attempts++; continue; }
            // Keep away from every existing base / stash / farm / schematic (base-spacing.min-gap)
            if (reg != null && gap > 0 && !reg.isClear(world, x - 32, z - 32, x + 32, z + 32, gap)) { attempts++; continue; }
            // Check spread from other bases
            boolean tooClose = false;
            for (int[] p : positions) {
                double dist = Math.sqrt(Math.pow(x - p[0], 2) + Math.pow(z - p[1], 2));
                if (dist < minDist) { tooClose = true; break; }
            }
            if (!tooClose) positions.add(new int[]{x, z});
            attempts++;
        }
        return positions;
    }

    public int getGeneratedCount() { return generatedCount; }
}
