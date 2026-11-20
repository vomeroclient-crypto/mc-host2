package com.donutsmp.plugin;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.Collections;

/**
 * Generates individual stashes on demand via /generatestash
 * These are smaller hidden rooms, good for scattering around the map.
 */
public class StashGenerator {

    private final DonutSMPPlugin plugin;
    private final Random random = new Random();

    private static final String[] STASH_NOTES = {
        "if you found this, respect",
        "my actual base is 10k out",
        "this isnt even my real stash",
        "gl finding the other ones",
        "season 2 loot :)",
        "dont tell anyone",
        "this took forever to hide"
    };

    public StashGenerator(DonutSMPPlugin plugin) {
        this.plugin = plugin;
    }

    public void generateStashAt(Location loc, String type) {
        World w = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY() - 5; // dig down a bit from player
        int z = loc.getBlockZ();

        // Remember it so spacing checks and /removebase know about this stash
        PlacementRegistry reg = plugin.getPlacementRegistry();
        if (reg != null) reg.register(PlacementRegistry.STASH, type, w, x - 4, y - 1, z - 4, x + 4, y + 5, z + 4);

        switch (type) {
            case "baltop": buildBaltopStash(w, x, y, z); break;
            case "media":  buildMediaStash(w, x, y, z); break;
            case "trap":   buildTrapStash(w, x, y, z); break;
            default:       buildNomadStashSmall(w, x, y, z); break;
        }
    }

    // Tiny 3x3 room, single chest, trapdoor disguise
    private void buildNomadStashSmall(World w, int x, int y, int z) {
        fillBox(w, x-1, y, z-1, x+1, y+2, z+1, Material.AIR);
        placeBlock(w, x, y+1, z-1, Material.TORCH);
        placeChestWithLoot(w, x, y+1, z, "nomad");
        // Cover with gravel
        placeBlock(w, x, y+3, z, Material.GRAVEL);
        placeBlock(w, x, y+4, z, Material.GRAVEL);
        placeNote(w, x+1, y+1, z);
    }

    // Mid-size room, multiple chests, looks lived in
    private void buildMediaStash(World w, int x, int y, int z) {
        fillBox(w, x-3, y, z-3, x+3, y+4, z+3, Material.AIR);
        outlineBox(w, x-3, y, z-3, x+3, y+4, z+3, Material.DEEPSLATE_BRICKS);
        fillLayer(w, x-3, y, z-3, x+3, z+3, Material.SMOOTH_STONE);
        // Lighting
        placeBlock(w, x, y+3, z, Material.GLOWSTONE);
        // Chests
        placeChestWithLoot(w, x-2, y+1, z-2, "media");
        placeChestWithLoot(w, x+2, y+1, z-2, "media");
        placeChestWithLoot(w, x-2, y+1, z+2, "main");
        placeChestWithLoot(w, x+2, y+1, z+2, "main");
        placeNote(w, x, y+1, z);
    }

    // Rich stash with diamond blocks on floor
    private void buildBaltopStash(World w, int x, int y, int z) {
        fillBox(w, x-2, y, z-2, x+2, y+3, z+2, Material.AIR);
        outlineBox(w, x-2, y, z-2, x+2, y+3, z+2, Material.OBSIDIAN);
        fillLayer(w, x-2, y, z-2, x+2, z+2, Material.DIAMOND_BLOCK);
        placeBlock(w, x, y+2, z, Material.GLOWSTONE);
        placeChestWithLoot(w, x-1, y+1, z-1, "baltop");
        placeChestWithLoot(w, x+1, y+1, z-1, "baltop");
        placeChestWithLoot(w, x, y+1, z+1, "media");
        placeNote(w, x-1, y+1, z+1);
    }

    // Looks great, fully trapped
    private void buildTrapStash(World w, int x, int y, int z) {
        fillBox(w, x-2, y, z-2, x+2, y+3, z+2, Material.AIR);
        outlineBox(w, x-2, y, z-2, x+2, y+3, z+2, Material.POLISHED_DEEPSLATE);
        fillLayer(w, x-2, y, z-2, x+2, z+2, Material.OAK_PLANKS); // fake floor
        // Lava under fake floor
        for (int tx = x-1; tx <= x+1; tx++)
            for (int tz = z-1; tz <= z+1; tz++)
                placeBlock(w, tx, y-1, tz, Material.LAVA);
        // Pressure plate + TNT
        placeBlock(w, x, y, z, Material.TNT);
        placeBlock(w, x, y+1, z, Material.STONE_PRESSURE_PLATE);
        // Tempting chest right next to plate
        placeChestWithLoot(w, x+2, y+1, z, "baltop");
        placeBlock(w, x+1, y+1, z, Material.DIAMOND_BLOCK);
        placeNote(w, x-2, y+1, z);
    }

    private void placeNote(World w, int x, int y, int z) {
        placeBlock(w, x, y, z, Material.OAK_SIGN);
        Block b = w.getBlockAt(x, y, z);
        if (b.getState() instanceof org.bukkit.block.Sign) {
            org.bukkit.block.Sign sign = (org.bukkit.block.Sign) b.getState();
            String note = STASH_NOTES[random.nextInt(STASH_NOTES.length)];
            sign.setLine(0, note.length() > 15 ? note.substring(0, 15) : note);
            sign.setLine(1, note.length() > 15 ? note.substring(15) : "");
            sign.update();
        }
    }

    private void placeChestWithLoot(World w, int x, int y, int z, String tier) {
        placeBlock(w, x, y, z, Material.CHEST);
        Block b = w.getBlockAt(x, y, z);
        if (b.getState() instanceof Chest) {
            Chest chest = (Chest) b.getState();
            List<ItemStack> loot = LootTable.getLoot(tier, random);

            // Fill ALL 27 slots — shuffle loot into sequential slots so
            // the chest looks completely packed, not half-empty.
            Collections.shuffle(loot, random);
            int slot = 0;
            for (ItemStack item : loot) {
                if (slot >= 27) break;
                chest.getInventory().setItem(slot++, item);
            }
            // If we still have empty slots, loop loot again (duplicates) to fill up
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
                    if (x==x1||x==x2||y==y1||y==y2||z==z1||z==z2)
                        placeBlock(w, x, y, z, mat);
                }
    }

    private void fillLayer(World w, int x1, int y, int z1, int x2, int z2, Material mat) {
        for (int x = Math.min(x1,x2); x <= Math.max(x1,x2); x++)
            for (int z = Math.min(z1,z2); z <= Math.max(z1,z2); z++)
                placeBlock(w, x, y, z, mat);
    }

    private void placeBlock(World w, int x, int y, int z, Material mat) {
        w.getBlockAt(x, y, z).setType(mat, false);
    }
}
