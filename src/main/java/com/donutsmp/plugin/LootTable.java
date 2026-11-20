package com.donutsmp.plugin;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class LootTable {

    /**
     * Get loot at default multiplier (1.0 = stash scale).
     */
    public static List<ItemStack> getLoot(String tier, Random random) {
        return getLoot(tier, random, 1.0);
    }

    /**
     * Get loot scaled by a multiplier.
     * multiplier > 1.0 = bigger base = more / better loot.
     * Used by BaseGenerator to scale chests to base size.
     */
    public static List<ItemStack> getLoot(String tier, Random random, double multiplier) {
        List<ItemStack> base;
        switch (tier.toLowerCase()) {
            case "nomad":   base = nomadLoot(random);   break;
            case "main":    base = mainLoot(random);    break;
            case "baltop":  base = baltopLoot(random);  break;
            case "media":   base = mediaLoot(random);   break;
            case "regear":  base = regearLoot(random);  break;
            default:        base = nomadLoot(random);   break;
        }
        if (multiplier <= 1.0) return base;
        return scaleLoot(base, random, multiplier);
    }

    /**
     * Scale a loot list: multiply stack sizes and add extra stacks
     * proportional to the multiplier so bigger bases feel genuinely richer.
     */
    private static List<ItemStack> scaleLoot(List<ItemStack> original, Random random, double multiplier) {
        List<ItemStack> scaled = new ArrayList<>();
        for (ItemStack item : original) {
            // Scale stack amount (cap at max stack size)
            int scaledAmt = (int) Math.min(item.getMaxStackSize(),
                    Math.round(item.getAmount() * multiplier));
            ItemStack copy = item.clone();
            copy.setAmount(scaledAmt);
            scaled.add(copy);

            // With multiplier > 1.5, add a duplicate stack at ~40% chance per item
            if (multiplier >= 1.5 && random.nextFloat() < 0.4f * (multiplier - 1.0)) {
                ItemStack extra = copy.clone();
                extra.setAmount(Math.max(1, scaledAmt / 2));
                scaled.add(extra);
            }
        }
        // For very big bases (multiplier >= 2) also sprinkle in bonus rare items
        if (multiplier >= 2.0) {
            if (random.nextFloat() < 0.6f) scaled.add(new ItemStack(Material.TOTEM_OF_UNDYING, 1));
            if (random.nextFloat() < 0.5f) scaled.add(new ItemStack(Material.NETHERITE_INGOT, 1 + random.nextInt(2)));
            if (random.nextFloat() < 0.4f) scaled.add(new ItemStack(Material.ELYTRA, 1));
            if (random.nextFloat() < 0.7f) scaled.add(new ItemStack(Material.ENCHANTED_BOOK, 1 + random.nextInt(3)));
        }
        return scaled;
    }

    // ----------------------------------------------------------------
    // BASE LOOT TABLES — now fill all 27 chest slots
    // ----------------------------------------------------------------

    private static List<ItemStack> nomadLoot(Random r) {
        List<ItemStack> l = new ArrayList<>();
        // Tools / weapons
        l.add(new ItemStack(Material.IRON_SWORD, 1));
        l.add(new ItemStack(Material.IRON_PICKAXE, 1));
        l.add(new ItemStack(Material.IRON_SHOVEL, 1));
        l.add(new ItemStack(Material.IRON_AXE, 1));
        // Armour
        l.add(new ItemStack(Material.IRON_HELMET, 1));
        l.add(new ItemStack(Material.IRON_CHESTPLATE, 1));
        l.add(new ItemStack(Material.IRON_LEGGINGS, 1));
        l.add(new ItemStack(Material.IRON_BOOTS, 1));
        // Food
        l.add(new ItemStack(Material.BREAD, 16 + r.nextInt(16)));
        l.add(new ItemStack(Material.COOKED_BEEF, 8 + r.nextInt(8)));
        // Resources
        l.add(new ItemStack(Material.IRON_INGOT, 16 + r.nextInt(16)));
        l.add(new ItemStack(Material.COAL, 32 + r.nextInt(32)));
        l.add(new ItemStack(Material.OAK_LOG, 64));
        l.add(new ItemStack(Material.COBBLESTONE, 64));
        l.add(new ItemStack(Material.TORCH, 32));
        // Utility
        l.add(new ItemStack(Material.CRAFTING_TABLE, 1));
        l.add(new ItemStack(Material.FURNACE, 1));
        l.add(new ItemStack(Material.CHEST, 2));
        l.add(new ItemStack(Material.FLINT_AND_STEEL, 1));
        l.add(new ItemStack(Material.BUCKET, 2));
        // Bonus
        l.add(new ItemStack(Material.GOLD_INGOT, 4 + r.nextInt(8)));
        if (r.nextFloat() < 0.5f) l.add(new ItemStack(Material.DIAMOND, 1 + r.nextInt(3)));
        if (r.nextFloat() < 0.3f) l.add(new ItemStack(Material.ENCHANTED_BOOK, 1));
        if (r.nextFloat() < 0.2f) l.add(new ItemStack(Material.GOLDEN_APPLE, 1 + r.nextInt(2)));
        // Filler to reach 27 slots
        l.add(new ItemStack(Material.ARROW, 32 + r.nextInt(32)));
        l.add(new ItemStack(Material.BOW, 1));
        l.add(new ItemStack(Material.FISHING_ROD, 1));
        return l;
    }

    private static List<ItemStack> mainLoot(Random r) {
        List<ItemStack> l = new ArrayList<>();
        // Gear
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addEnchantment(Enchantment.DAMAGE_ALL, 3);
        sword.addEnchantment(Enchantment.DURABILITY, 2);
        l.add(sword);
        ItemStack pick = new ItemStack(Material.DIAMOND_PICKAXE);
        pick.addEnchantment(Enchantment.DIG_SPEED, 4);
        pick.addEnchantment(Enchantment.DURABILITY, 3);
        l.add(pick);
        // Armour
        ItemStack chestplate = new ItemStack(Material.DIAMOND_CHESTPLATE);
        chestplate.addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 3);
        l.add(chestplate);
        l.add(new ItemStack(Material.DIAMOND_HELMET, 1));
        l.add(new ItemStack(Material.DIAMOND_LEGGINGS, 1));
        l.add(new ItemStack(Material.DIAMOND_BOOTS, 1));
        // Resources
        l.add(new ItemStack(Material.DIAMOND, 8 + r.nextInt(8)));
        l.add(new ItemStack(Material.IRON_INGOT, 32 + r.nextInt(32)));
        l.add(new ItemStack(Material.GOLD_INGOT, 16 + r.nextInt(16)));
        l.add(new ItemStack(Material.OBSIDIAN, 16 + r.nextInt(16)));
        l.add(new ItemStack(Material.LAPIS_LAZULI, 32 + r.nextInt(32)));
        // Food
        l.add(new ItemStack(Material.GOLDEN_APPLE, 4 + r.nextInt(4)));
        l.add(new ItemStack(Material.COOKED_BEEF, 64));
        // Potions / misc
        l.add(new ItemStack(Material.ENCHANTED_BOOK, 2 + r.nextInt(2)));
        l.add(new ItemStack(Material.EXPERIENCE_BOTTLE, 16 + r.nextInt(16)));
        l.add(new ItemStack(Material.ENDER_PEARL, 8 + r.nextInt(8)));
        l.add(new ItemStack(Material.BLAZE_ROD, 8 + r.nextInt(8)));
        // Blocks
        l.add(new ItemStack(Material.IRON_BLOCK, 4 + r.nextInt(4)));
        l.add(new ItemStack(Material.GOLD_BLOCK, 2 + r.nextInt(3)));
        // Bonus rare
        if (r.nextFloat() < 0.3f) l.add(new ItemStack(Material.NETHERITE_SCRAP, 1 + r.nextInt(2)));
        if (r.nextFloat() < 0.4f) l.add(new ItemStack(Material.TOTEM_OF_UNDYING, 1));
        if (r.nextFloat() < 0.2f) l.add(new ItemStack(Material.ELYTRA, 1));
        // Fillers
        l.add(new ItemStack(Material.ARROW, 64));
        l.add(new ItemStack(Material.FIREWORK_ROCKET, 16));
        l.add(new ItemStack(Material.SHULKER_BOX, 1));
        l.add(new ItemStack(Material.TRIDENT, 1));
        return l;
    }

    private static List<ItemStack> baltopLoot(Random r) {
        List<ItemStack> l = new ArrayList<>();
        // Netherite gear (full set)
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.addEnchantment(Enchantment.DAMAGE_ALL, 5);
        sword.addEnchantment(Enchantment.DURABILITY, 3);
        sword.addEnchantment(Enchantment.FIRE_ASPECT, 2);
        l.add(sword);
        ItemStack pick = new ItemStack(Material.NETHERITE_PICKAXE);
        pick.addEnchantment(Enchantment.DIG_SPEED, 5);
        pick.addEnchantment(Enchantment.DURABILITY, 3);
        pick.addEnchantment(Enchantment.LOOT_BONUS_BLOCKS, 3);
        l.add(pick);
        ItemStack helmet = new ItemStack(Material.NETHERITE_HELMET);
        helmet.addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        helmet.addEnchantment(Enchantment.DURABILITY, 3);
        helmet.addEnchantment(Enchantment.WATER_WORKER, 1);
        l.add(helmet);
        ItemStack chest = new ItemStack(Material.NETHERITE_CHESTPLATE);
        chest.addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        chest.addEnchantment(Enchantment.DURABILITY, 3);
        l.add(chest);
        ItemStack legs = new ItemStack(Material.NETHERITE_LEGGINGS);
        legs.addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        legs.addEnchantment(Enchantment.DURABILITY, 3);
        l.add(legs);
        ItemStack boots = new ItemStack(Material.NETHERITE_BOOTS);
        boots.addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        boots.addEnchantment(Enchantment.DURABILITY, 3);
        boots.addEnchantment(Enchantment.DEPTH_STRIDER, 3);
        l.add(boots);
        // Raw wealth
        l.add(new ItemStack(Material.DIAMOND, 32 + r.nextInt(32)));
        l.add(new ItemStack(Material.DIAMOND_BLOCK, 4 + r.nextInt(8)));
        l.add(new ItemStack(Material.NETHERITE_INGOT, 2 + r.nextInt(4)));
        l.add(new ItemStack(Material.NETHERITE_SCRAP, 4 + r.nextInt(4)));
        l.add(new ItemStack(Material.GOLD_BLOCK, 8 + r.nextInt(8)));
        l.add(new ItemStack(Material.IRON_BLOCK, 16 + r.nextInt(16)));
        // Combat consumables
        l.add(new ItemStack(Material.GOLDEN_APPLE, 16 + r.nextInt(16)));
        l.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1 + r.nextInt(3)));
        l.add(new ItemStack(Material.TOTEM_OF_UNDYING, 2 + r.nextInt(3)));
        // Magic / utility
        l.add(new ItemStack(Material.ENCHANTED_BOOK, 4 + r.nextInt(4)));
        l.add(new ItemStack(Material.EXPERIENCE_BOTTLE, 32 + r.nextInt(32)));
        l.add(new ItemStack(Material.ENDER_PEARL, 16 + r.nextInt(16)));
        // Elytra + rockets
        l.add(new ItemStack(Material.ELYTRA, 1));
        l.add(new ItemStack(Material.FIREWORK_ROCKET, 64));
        // Shulkers
        l.add(new ItemStack(Material.SHULKER_BOX, 2 + r.nextInt(3)));
        // Trident + mending book always here
        ItemStack trident = new ItemStack(Material.TRIDENT);
        trident.addEnchantment(Enchantment.LOYALTY, 3);
        l.add(trident);
        ItemStack mending = new ItemStack(Material.ENCHANTED_BOOK);
        mending.addEnchantment(Enchantment.MENDING, 1);
        l.add(mending);
        return l;
    }

    // ----------------------------------------------------------------
    // REGEAR LOOT — full netherite kit with totems so players can
    // immediately get back into action after dying. Every chest in a
    // regear base uses this table.
    // ----------------------------------------------------------------
    private static List<ItemStack> regearLoot(Random r) {
        List<ItemStack> l = new ArrayList<>();

        // --- Netherite Helmet: Prot IV, Unbreaking III, Mending, Respiration III ---
        ItemStack helmet = new ItemStack(Material.NETHERITE_HELMET);
        helmet.addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        helmet.addEnchantment(Enchantment.DURABILITY, 3);
        helmet.addEnchantment(Enchantment.MENDING, 1);
        helmet.addEnchantment(Enchantment.OXYGEN, 3);
        l.add(helmet);

        // --- Netherite Chestplate: Prot IV, Unbreaking III, Mending ---
        ItemStack chest = new ItemStack(Material.NETHERITE_CHESTPLATE);
        chest.addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        chest.addEnchantment(Enchantment.DURABILITY, 3);
        chest.addEnchantment(Enchantment.MENDING, 1);
        l.add(chest);

        // --- Netherite Leggings: Prot IV, Unbreaking III, Mending ---
        ItemStack legs = new ItemStack(Material.NETHERITE_LEGGINGS);
        legs.addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        legs.addEnchantment(Enchantment.DURABILITY, 3);
        legs.addEnchantment(Enchantment.MENDING, 1);
        l.add(legs);

        // --- Netherite Boots: Prot IV, Unbreaking III, Mending, FeatherFalling IV, Depth Strider III ---
        ItemStack boots = new ItemStack(Material.NETHERITE_BOOTS);
        boots.addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        boots.addEnchantment(Enchantment.DURABILITY, 3);
        boots.addEnchantment(Enchantment.MENDING, 1);
        boots.addEnchantment(Enchantment.PROTECTION_FALL, 4);
        boots.addEnchantment(Enchantment.DEPTH_STRIDER, 3);
        l.add(boots);

        // --- Netherite Sword: Sharpness V, Unbreaking III, Mending, Fire Aspect II, Looting III ---
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.addEnchantment(Enchantment.DAMAGE_ALL, 5);
        sword.addEnchantment(Enchantment.DURABILITY, 3);
        sword.addEnchantment(Enchantment.MENDING, 1);
        sword.addEnchantment(Enchantment.FIRE_ASPECT, 2);
        sword.addEnchantment(Enchantment.LOOT_BONUS_MOBS, 3);
        l.add(sword);

        // --- Netherite Pickaxe: Efficiency V, Unbreaking III, Mending, Fortune III ---
        ItemStack pick = new ItemStack(Material.NETHERITE_PICKAXE);
        pick.addEnchantment(Enchantment.DIG_SPEED, 5);
        pick.addEnchantment(Enchantment.DURABILITY, 3);
        pick.addEnchantment(Enchantment.MENDING, 1);
        pick.addEnchantment(Enchantment.LOOT_BONUS_BLOCKS, 3);
        l.add(pick);

        // --- Netherite Axe: Sharpness V, Efficiency V, Unbreaking III, Mending ---
        ItemStack axe = new ItemStack(Material.NETHERITE_AXE);
        axe.addEnchantment(Enchantment.DAMAGE_ALL, 5);
        axe.addEnchantment(Enchantment.DIG_SPEED, 5);
        axe.addEnchantment(Enchantment.DURABILITY, 3);
        axe.addEnchantment(Enchantment.MENDING, 1);
        l.add(axe);

        // --- Totems: always 3-5 per chest ---
        l.add(new ItemStack(Material.TOTEM_OF_UNDYING, 3 + r.nextInt(3)));

        // --- Elytra (always present in regear) ---
        l.add(new ItemStack(Material.ELYTRA, 1));

        // --- Firework rockets for elytra ---
        l.add(new ItemStack(Material.FIREWORK_ROCKET, 64));

        // --- Combat consumables ---
        l.add(new ItemStack(Material.GOLDEN_APPLE, 16 + r.nextInt(16)));
        l.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2 + r.nextInt(3)));

        // --- Ender pearls ---
        l.add(new ItemStack(Material.ENDER_PEARL, 16 + r.nextInt(16)));

        // --- Netherite ingots (spare for anvil repairs) ---
        l.add(new ItemStack(Material.NETHERITE_INGOT, 2 + r.nextInt(3)));

        // --- Bow: Power V, Unbreaking III, Mending, Infinity ---
        ItemStack bow = new ItemStack(Material.BOW);
        bow.addEnchantment(Enchantment.ARROW_DAMAGE, 5);
        bow.addEnchantment(Enchantment.DURABILITY, 3);
        bow.addEnchantment(Enchantment.MENDING, 1);
        bow.addEnchantment(Enchantment.ARROW_INFINITE, 1);
        l.add(bow);
        l.add(new ItemStack(Material.ARROW, 1)); // 1 arrow required for Infinity

        // --- Crossbow: Quick Charge III, Piercing IV, Unbreaking III, Mending ---
        ItemStack crossbow = new ItemStack(Material.CROSSBOW);
        crossbow.addEnchantment(Enchantment.QUICK_CHARGE, 3);
        crossbow.addEnchantment(Enchantment.PIERCING, 4);
        crossbow.addEnchantment(Enchantment.DURABILITY, 3);
        crossbow.addEnchantment(Enchantment.MENDING, 1);
        l.add(crossbow);

        // --- Food ---
        l.add(new ItemStack(Material.COOKED_BEEF, 64));

        // --- Shulker boxes (empty, for carrying loot) ---
        l.add(new ItemStack(Material.SHULKER_BOX, 2 + r.nextInt(2)));

        // --- Spare enchanted books ---
        l.add(new ItemStack(Material.ENCHANTED_BOOK, 2 + r.nextInt(3)));

        // --- Extra netherite scrap for future upgrades ---
        l.add(new ItemStack(Material.NETHERITE_SCRAP, 2 + r.nextInt(4)));

        // --- XP bottles ---
        l.add(new ItemStack(Material.EXPERIENCE_BOTTLE, 32 + r.nextInt(32)));

        // --- Obsidian (for respawn anchors / bases) ---
        l.add(new ItemStack(Material.OBSIDIAN, 16 + r.nextInt(16)));

        return l;
    }

    private static List<ItemStack> mediaLoot(Random r) {
        List<ItemStack> l = new ArrayList<>();
        // Full netherite kit with silk/fortune picks
        ItemStack silkPick = new ItemStack(Material.NETHERITE_PICKAXE);
        silkPick.addEnchantment(Enchantment.SILK_TOUCH, 1);
        silkPick.addEnchantment(Enchantment.DIG_SPEED, 5);
        silkPick.addEnchantment(Enchantment.DURABILITY, 3);
        l.add(silkPick);
        ItemStack fortunePick = new ItemStack(Material.NETHERITE_PICKAXE);
        fortunePick.addEnchantment(Enchantment.LOOT_BONUS_BLOCKS, 3);
        fortunePick.addEnchantment(Enchantment.DIG_SPEED, 5);
        fortunePick.addEnchantment(Enchantment.DURABILITY, 3);
        l.add(fortunePick);
        // Netherite armour — all prot 4 mending
        ItemStack helmet = new ItemStack(Material.NETHERITE_HELMET);
        helmet.addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        helmet.addEnchantment(Enchantment.MENDING, 1);
        helmet.addEnchantment(Enchantment.DURABILITY, 3);
        l.add(helmet);
        ItemStack chest = new ItemStack(Material.NETHERITE_CHESTPLATE);
        chest.addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        chest.addEnchantment(Enchantment.MENDING, 1);
        chest.addEnchantment(Enchantment.DURABILITY, 3);
        l.add(chest);
        ItemStack legs = new ItemStack(Material.NETHERITE_LEGGINGS);
        legs.addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        legs.addEnchantment(Enchantment.MENDING, 1);
        legs.addEnchantment(Enchantment.DURABILITY, 3);
        l.add(legs);
        ItemStack boots = new ItemStack(Material.NETHERITE_BOOTS);
        boots.addEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        boots.addEnchantment(Enchantment.MENDING, 1);
        boots.addEnchantment(Enchantment.DEPTH_STRIDER, 3);
        boots.addEnchantment(Enchantment.DURABILITY, 3);
        l.add(boots);
        // Wealth
        l.add(new ItemStack(Material.DIAMOND_BLOCK, 8 + r.nextInt(8)));
        l.add(new ItemStack(Material.NETHERITE_INGOT, 4 + r.nextInt(4)));
        l.add(new ItemStack(Material.GOLD_BLOCK, 16 + r.nextInt(16)));
        l.add(new ItemStack(Material.ANCIENT_DEBRIS, 2 + r.nextInt(3)));
        // Combat
        l.add(new ItemStack(Material.GOLDEN_APPLE, 32 + r.nextInt(32)));
        l.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2 + r.nextInt(4)));
        l.add(new ItemStack(Material.TOTEM_OF_UNDYING, 4 + r.nextInt(4)));
        // Elytra, rockets, pearls
        l.add(new ItemStack(Material.ELYTRA, 1));
        l.add(new ItemStack(Material.FIREWORK_ROCKET, 64));
        l.add(new ItemStack(Material.ENDER_PEARL, 32 + r.nextInt(32)));
        // Shulkers full of books
        l.add(new ItemStack(Material.SHULKER_BOX, 3 + r.nextInt(3)));
        l.add(new ItemStack(Material.ENCHANTED_BOOK, 8 + r.nextInt(8)));
        // Beacons and nether stars
        l.add(new ItemStack(Material.BEACON, 1 + r.nextInt(2)));
        l.add(new ItemStack(Material.NETHER_STAR, 1 + r.nextInt(2)));
        // XP
        l.add(new ItemStack(Material.EXPERIENCE_BOTTLE, 64));
        return l;
    }
}
