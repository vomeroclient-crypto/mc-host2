package com.donutsmp.plugin;

import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * /sell hand | /sell all
 *
 * Everything sellable — raw materials, tools, weapons, armor,
 * enchanted gear (priced by enchant tier on top of base), potions,
 * spawn eggs, music discs, and more.
 *
 * Base prices reflect DonutSMP March 2026 economy.
 * Enchanted items get a bonus multiplier on top of base value.
 */
public class SellManager {

    private final DonutSMPPlugin plugin;

    private final Map<UUID, Double> balances = new HashMap<>();

    // ── Base prices (per single item) ─────────────────────────────────────────
    private static final Map<Material, Double> PRICES = new HashMap<>();
    static {

        // ── Diamonds / netherite ──────────────────────────────────────────────
        PRICES.put(Material.DIAMOND,                       50.0);
        PRICES.put(Material.DIAMOND_BLOCK,                450.0);
        PRICES.put(Material.DIAMOND_ORE,                   30.0);
        PRICES.put(Material.DEEPSLATE_DIAMOND_ORE,         30.0);
        PRICES.put(Material.NETHERITE_INGOT,             2000.0);
        PRICES.put(Material.NETHERITE_SCRAP,              500.0);
        PRICES.put(Material.ANCIENT_DEBRIS,               600.0);
        PRICES.put(Material.NETHERITE_BLOCK,            18000.0);

        // ── Diamond tools / weapons / armor ───────────────────────────────────
        PRICES.put(Material.DIAMOND_SWORD,                120.0);
        PRICES.put(Material.DIAMOND_AXE,                  110.0);
        PRICES.put(Material.DIAMOND_PICKAXE,              130.0);
        PRICES.put(Material.DIAMOND_SHOVEL,                90.0);
        PRICES.put(Material.DIAMOND_HOE,                   80.0);
        PRICES.put(Material.DIAMOND_HELMET,               150.0);
        PRICES.put(Material.DIAMOND_CHESTPLATE,           220.0);
        PRICES.put(Material.DIAMOND_LEGGINGS,             200.0);
        PRICES.put(Material.DIAMOND_BOOTS,                140.0);

        // ── Netherite tools / weapons / armor ─────────────────────────────────
        PRICES.put(Material.NETHERITE_SWORD,             2500.0);
        PRICES.put(Material.NETHERITE_AXE,               2400.0);
        PRICES.put(Material.NETHERITE_PICKAXE,           2600.0);
        PRICES.put(Material.NETHERITE_SHOVEL,            2200.0);
        PRICES.put(Material.NETHERITE_HOE,               2100.0);
        PRICES.put(Material.NETHERITE_HELMET,            3000.0);
        PRICES.put(Material.NETHERITE_CHESTPLATE,        4000.0);
        PRICES.put(Material.NETHERITE_LEGGINGS,          3800.0);
        PRICES.put(Material.NETHERITE_BOOTS,             3200.0);

        // ── Iron tools / weapons / armor ──────────────────────────────────────
        PRICES.put(Material.IRON_SWORD,                    8.0);
        PRICES.put(Material.IRON_AXE,                      7.0);
        PRICES.put(Material.IRON_PICKAXE,                  9.0);
        PRICES.put(Material.IRON_SHOVEL,                   6.0);
        PRICES.put(Material.IRON_HOE,                      5.0);
        PRICES.put(Material.IRON_HELMET,                  10.0);
        PRICES.put(Material.IRON_CHESTPLATE,              16.0);
        PRICES.put(Material.IRON_LEGGINGS,                14.0);
        PRICES.put(Material.IRON_BOOTS,                   10.0);

        // ── Gold tools / weapons / armor ──────────────────────────────────────
        PRICES.put(Material.GOLDEN_SWORD,                  6.0);
        PRICES.put(Material.GOLDEN_AXE,                    5.0);
        PRICES.put(Material.GOLDEN_PICKAXE,                6.0);
        PRICES.put(Material.GOLDEN_SHOVEL,                 4.0);
        PRICES.put(Material.GOLDEN_HOE,                    4.0);
        PRICES.put(Material.GOLDEN_HELMET,                 8.0);
        PRICES.put(Material.GOLDEN_CHESTPLATE,            12.0);
        PRICES.put(Material.GOLDEN_LEGGINGS,              11.0);
        PRICES.put(Material.GOLDEN_BOOTS,                  8.0);

        // ── Stone tools ───────────────────────────────────────────────────────
        PRICES.put(Material.STONE_SWORD,                   1.0);
        PRICES.put(Material.STONE_AXE,                     1.0);
        PRICES.put(Material.STONE_PICKAXE,                 1.0);
        PRICES.put(Material.STONE_SHOVEL,                  0.5);
        PRICES.put(Material.STONE_HOE,                     0.5);

        // ── Wooden tools (almost worthless but sellable) ──────────────────────
        PRICES.put(Material.WOODEN_SWORD,                  0.3);
        PRICES.put(Material.WOODEN_AXE,                    0.3);
        PRICES.put(Material.WOODEN_PICKAXE,                0.3);
        PRICES.put(Material.WOODEN_SHOVEL,                 0.2);
        PRICES.put(Material.WOODEN_HOE,                    0.2);

        // ── Leather armor ─────────────────────────────────────────────────────
        PRICES.put(Material.LEATHER_HELMET,                1.0);
        PRICES.put(Material.LEATHER_CHESTPLATE,            2.0);
        PRICES.put(Material.LEATHER_LEGGINGS,              1.5);
        PRICES.put(Material.LEATHER_BOOTS,                 1.0);

        // ── Chainmail armor ───────────────────────────────────────────────────
        PRICES.put(Material.CHAINMAIL_HELMET,              5.0);
        PRICES.put(Material.CHAINMAIL_CHESTPLATE,          8.0);
        PRICES.put(Material.CHAINMAIL_LEGGINGS,            7.0);
        PRICES.put(Material.CHAINMAIL_BOOTS,               5.0);

        // ── Turtle / elytra / trident / shield ───────────────────────────────
        PRICES.put(Material.TURTLE_HELMET,                50.0);
        PRICES.put(Material.ELYTRA,                     1500.0);
        PRICES.put(Material.TRIDENT,                     400.0);
        PRICES.put(Material.SHIELD,                        5.0);
        PRICES.put(Material.CROSSBOW,                     12.0);
        PRICES.put(Material.BOW,                           5.0);

        // ── Totems / special combat ───────────────────────────────────────────
        PRICES.put(Material.TOTEM_OF_UNDYING,            300.0);
        PRICES.put(Material.GOLDEN_APPLE,                 20.0);
        PRICES.put(Material.ENCHANTED_GOLDEN_APPLE,      500.0);

        // ── Shards / crystals / special blocks ───────────────────────────────
        PRICES.put(Material.AMETHYST_SHARD,                1.5);
        PRICES.put(Material.AMETHYST_BLOCK,               12.0);
        PRICES.put(Material.BUDDING_AMETHYST,             50.0);
        PRICES.put(Material.ECHO_SHARD,                  200.0);
        PRICES.put(Material.DISC_FRAGMENT_5,              30.0);
        PRICES.put(Material.RECOVERY_COMPASS,            300.0);

        // ── Gold ──────────────────────────────────────────────────────────────
        PRICES.put(Material.GOLD_INGOT,                    8.0);
        PRICES.put(Material.GOLD_BLOCK,                   72.0);
        PRICES.put(Material.GOLD_ORE,                      5.0);
        PRICES.put(Material.DEEPSLATE_GOLD_ORE,            5.0);
        PRICES.put(Material.NETHER_GOLD_ORE,               3.0);
        PRICES.put(Material.RAW_GOLD,                      6.0);
        PRICES.put(Material.GOLD_NUGGET,                   1.0);

        // ── Iron ──────────────────────────────────────────────────────────────
        PRICES.put(Material.IRON_INGOT,                    2.0);
        PRICES.put(Material.IRON_BLOCK,                   18.0);
        PRICES.put(Material.IRON_ORE,                      1.5);
        PRICES.put(Material.DEEPSLATE_IRON_ORE,            1.5);
        PRICES.put(Material.RAW_IRON,                      1.5);
        PRICES.put(Material.IRON_NUGGET,                   0.2);

        // ── Emeralds ──────────────────────────────────────────────────────────
        PRICES.put(Material.EMERALD,                      12.0);
        PRICES.put(Material.EMERALD_BLOCK,               108.0);
        PRICES.put(Material.EMERALD_ORE,                   8.0);
        PRICES.put(Material.DEEPSLATE_EMERALD_ORE,         8.0);

        // ── Lapis ─────────────────────────────────────────────────────────────
        PRICES.put(Material.LAPIS_LAZULI,                  2.0);
        PRICES.put(Material.LAPIS_BLOCK,                  18.0);
        PRICES.put(Material.LAPIS_ORE,                     2.0);
        PRICES.put(Material.DEEPSLATE_LAPIS_ORE,           2.0);

        // ── Redstone ──────────────────────────────────────────────────────────
        PRICES.put(Material.REDSTONE,                      1.0);
        PRICES.put(Material.REDSTONE_BLOCK,                9.0);
        PRICES.put(Material.REDSTONE_ORE,                  1.0);
        PRICES.put(Material.DEEPSLATE_REDSTONE_ORE,        1.0);
        PRICES.put(Material.REPEATER,                      2.0);
        PRICES.put(Material.COMPARATOR,                    3.0);
        PRICES.put(Material.OBSERVER,                      3.0);
        PRICES.put(Material.PISTON,                        2.0);
        PRICES.put(Material.STICKY_PISTON,                 3.0);
        PRICES.put(Material.DROPPER,                       1.0);
        PRICES.put(Material.DISPENSER,                     2.0);
        PRICES.put(Material.HOPPER,                        5.0);

        // ── Coal ──────────────────────────────────────────────────────────────
        PRICES.put(Material.COAL,                          0.5);
        PRICES.put(Material.COAL_BLOCK,                    4.5);
        PRICES.put(Material.COAL_ORE,                      0.5);
        PRICES.put(Material.DEEPSLATE_COAL_ORE,            0.5);
        PRICES.put(Material.CHARCOAL,                      0.4);

        // ── Copper ────────────────────────────────────────────────────────────
        PRICES.put(Material.COPPER_INGOT,                  0.5);
        PRICES.put(Material.COPPER_BLOCK,                  4.5);
        PRICES.put(Material.RAW_COPPER,                    0.4);
        PRICES.put(Material.COPPER_ORE,                    0.4);
        PRICES.put(Material.DEEPSLATE_COPPER_ORE,          0.4);

        // ── Nether materials ──────────────────────────────────────────────────
        PRICES.put(Material.QUARTZ,                        1.5);
        PRICES.put(Material.QUARTZ_BLOCK,                 12.0);
        PRICES.put(Material.NETHER_QUARTZ_ORE,             1.5);
        PRICES.put(Material.BLAZE_ROD,                     8.0);
        PRICES.put(Material.BLAZE_POWDER,                  4.0);
        PRICES.put(Material.MAGMA_CREAM,                   3.0);
        PRICES.put(Material.GHAST_TEAR,                   25.0);
        PRICES.put(Material.NETHER_STAR,                5000.0);
        PRICES.put(Material.NETHER_BRICK,                  0.5);
        PRICES.put(Material.SOUL_SAND,                     0.3);
        PRICES.put(Material.SOUL_SOIL,                     0.3);
        PRICES.put(Material.GLOWSTONE_DUST,                1.0);
        PRICES.put(Material.GLOWSTONE,                     4.0);
        PRICES.put(Material.MAGMA_BLOCK,                   0.5);
        PRICES.put(Material.WITHER_SKELETON_SKULL,       200.0);
        PRICES.put(Material.SKELETON_SKULL,               15.0);
        PRICES.put(Material.ZOMBIE_HEAD,                  10.0);
        PRICES.put(Material.CREEPER_HEAD,                 20.0);
        PRICES.put(Material.PIGLIN_HEAD,                  15.0);
        PRICES.put(Material.NETHER_WART,                   1.0);
        PRICES.put(Material.NETHER_WART_BLOCK,             8.0);
        PRICES.put(Material.WARPED_WART_BLOCK,             6.0);
        PRICES.put(Material.BASALT,                        0.3);
        PRICES.put(Material.BLACKSTONE,                    0.3);
        PRICES.put(Material.GILDED_BLACKSTONE,             5.0);
        PRICES.put(Material.CRYING_OBSIDIAN,               4.0);
        PRICES.put(Material.RESPAWN_ANCHOR,               50.0);
        PRICES.put(Material.LODESTONE,                    30.0);
        PRICES.put(Material.PIGLIN_BRUTE_SPAWN_EGG,       25.0);
        PRICES.put(Material.HOGLIN_SPAWN_EGG,             20.0);

        // ── End materials ─────────────────────────────────────────────────────
        PRICES.put(Material.ENDER_PEARL,                   5.0);
        PRICES.put(Material.ENDER_EYE,                    15.0);
        PRICES.put(Material.SHULKER_SHELL,                40.0);
        PRICES.put(Material.SHULKER_BOX,                  80.0);
        PRICES.put(Material.WHITE_SHULKER_BOX,            80.0);
        PRICES.put(Material.ORANGE_SHULKER_BOX,           80.0);
        PRICES.put(Material.MAGENTA_SHULKER_BOX,          80.0);
        PRICES.put(Material.LIGHT_BLUE_SHULKER_BOX,       80.0);
        PRICES.put(Material.YELLOW_SHULKER_BOX,           80.0);
        PRICES.put(Material.LIME_SHULKER_BOX,             80.0);
        PRICES.put(Material.PINK_SHULKER_BOX,             80.0);
        PRICES.put(Material.GRAY_SHULKER_BOX,             80.0);
        PRICES.put(Material.LIGHT_GRAY_SHULKER_BOX,       80.0);
        PRICES.put(Material.CYAN_SHULKER_BOX,             80.0);
        PRICES.put(Material.PURPLE_SHULKER_BOX,           80.0);
        PRICES.put(Material.BLUE_SHULKER_BOX,             80.0);
        PRICES.put(Material.BROWN_SHULKER_BOX,            80.0);
        PRICES.put(Material.GREEN_SHULKER_BOX,            80.0);
        PRICES.put(Material.RED_SHULKER_BOX,              80.0);
        PRICES.put(Material.BLACK_SHULKER_BOX,            80.0);
        PRICES.put(Material.END_CRYSTAL,                  30.0);
        PRICES.put(Material.CHORUS_FRUIT,                  1.0);
        PRICES.put(Material.POPPED_CHORUS_FRUIT,           1.5);
        PRICES.put(Material.PURPUR_BLOCK,                  0.5);
        PRICES.put(Material.END_STONE,                     0.5);
        PRICES.put(Material.END_STONE_BRICKS,              0.6);
        PRICES.put(Material.DRAGON_EGG,              10000.0);
        PRICES.put(Material.DRAGON_BREATH,                15.0);
        PRICES.put(Material.DRAGON_HEAD,                 500.0);

        // ── Enchanting / brewing ──────────────────────────────────────────────
        PRICES.put(Material.ENCHANTED_BOOK,               40.0);
        PRICES.put(Material.EXPERIENCE_BOTTLE,             3.0);
        PRICES.put(Material.BOOKSHELF,                     2.0);
        PRICES.put(Material.BOOK,                          0.5);
        PRICES.put(Material.PAPER,                         0.1);
        PRICES.put(Material.GLASS_BOTTLE,                  0.3);
        PRICES.put(Material.BREWING_STAND,                 5.0);
        PRICES.put(Material.CAULDRON,                      3.0);
        PRICES.put(Material.FERMENTED_SPIDER_EYE,          2.0);
        PRICES.put(Material.DRAGON_BREATH,                15.0);
        PRICES.put(Material.PHANTOM_MEMBRANE,              5.0);
        PRICES.put(Material.RABBIT_FOOT,                   5.0);
        PRICES.put(Material.PUFFERFISH,                    3.0);
        PRICES.put(Material.MAGMA_CREAM,                   3.0);
        PRICES.put(Material.SUGAR,                         0.2);

        // ── Potions (base — splash/lingering worth more) ──────────────────────
        PRICES.put(Material.POTION,                        5.0);
        PRICES.put(Material.SPLASH_POTION,                 7.0);
        PRICES.put(Material.LINGERING_POTION,             10.0);
        PRICES.put(Material.TIPPED_ARROW,                  2.0);

        // ── Name tag / utility ────────────────────────────────────────────────
        PRICES.put(Material.NAME_TAG,                     10.0);
        PRICES.put(Material.SADDLE,                       15.0);
        PRICES.put(Material.LEAD,                          2.0);
        PRICES.put(Material.COMPASS,                       1.0);
        PRICES.put(Material.CLOCK,                         3.0);
        PRICES.put(Material.SPYGLASS,                     10.0);
        PRICES.put(Material.HEART_OF_THE_SEA,            250.0);
        PRICES.put(Material.NAUTILUS_SHELL,               20.0);
        PRICES.put(Material.CONDUIT,                     500.0);

        // ── Music discs ───────────────────────────────────────────────────────
        PRICES.put(Material.MUSIC_DISC_13,                20.0);
        PRICES.put(Material.MUSIC_DISC_CAT,               20.0);
        PRICES.put(Material.MUSIC_DISC_BLOCKS,            20.0);
        PRICES.put(Material.MUSIC_DISC_CHIRP,             20.0);
        PRICES.put(Material.MUSIC_DISC_FAR,               20.0);
        PRICES.put(Material.MUSIC_DISC_MALL,              20.0);
        PRICES.put(Material.MUSIC_DISC_MELLOHI,           20.0);
        PRICES.put(Material.MUSIC_DISC_STAL,              20.0);
        PRICES.put(Material.MUSIC_DISC_STRAD,             20.0);
        PRICES.put(Material.MUSIC_DISC_WARD,              20.0);
        PRICES.put(Material.MUSIC_DISC_11,                25.0);
        PRICES.put(Material.MUSIC_DISC_WAIT,              20.0);
        PRICES.put(Material.MUSIC_DISC_OTHERSIDE,         40.0);
        PRICES.put(Material.MUSIC_DISC_5,                 50.0);
        PRICES.put(Material.MUSIC_DISC_PIGSTEP,           60.0);
        PRICES.put(Material.MUSIC_DISC_RELIC,             55.0);

        // ── Spawn eggs ────────────────────────────────────────────────────────
        PRICES.put(Material.CREEPER_SPAWN_EGG,            15.0);
        PRICES.put(Material.SKELETON_SPAWN_EGG,           10.0);
        PRICES.put(Material.ZOMBIE_SPAWN_EGG,              8.0);
        PRICES.put(Material.SPIDER_SPAWN_EGG,              8.0);
        PRICES.put(Material.CAVE_SPIDER_SPAWN_EGG,        12.0);
        PRICES.put(Material.ENDERMAN_SPAWN_EGG,           20.0);
        PRICES.put(Material.BLAZE_SPAWN_EGG,              15.0);
        PRICES.put(Material.WITCH_SPAWN_EGG,              15.0);
        PRICES.put(Material.WITHER_SKELETON_SPAWN_EGG,    30.0);
        PRICES.put(Material.GUARDIAN_SPAWN_EGG,           20.0);
        PRICES.put(Material.ELDER_GUARDIAN_SPAWN_EGG,     80.0);
        PRICES.put(Material.SHULKER_SPAWN_EGG,            50.0);
        PRICES.put(Material.PHANTOM_SPAWN_EGG,            20.0);
        PRICES.put(Material.DROWNED_SPAWN_EGG,            12.0);
        PRICES.put(Material.PILLAGER_SPAWN_EGG,           15.0);
        PRICES.put(Material.VINDICATOR_SPAWN_EGG,         15.0);
        PRICES.put(Material.EVOKER_SPAWN_EGG,             40.0);
        PRICES.put(Material.RAVAGER_SPAWN_EGG,            60.0);
        PRICES.put(Material.WARDEN_SPAWN_EGG,            200.0);
        PRICES.put(Material.ENDERMITE_SPAWN_EGG,          10.0);
        PRICES.put(Material.SILVERFISH_SPAWN_EGG,          5.0);
        PRICES.put(Material.SLIME_SPAWN_EGG,              15.0);
        PRICES.put(Material.MAGMA_CUBE_SPAWN_EGG,         15.0);
        PRICES.put(Material.STRIDER_SPAWN_EGG,            20.0);
        PRICES.put(Material.PIGLIN_SPAWN_EGG,             15.0);
        PRICES.put(Material.ZOMBIFIED_PIGLIN_SPAWN_EGG,   10.0);
        PRICES.put(Material.COW_SPAWN_EGG,                 5.0);
        PRICES.put(Material.PIG_SPAWN_EGG,                 5.0);
        PRICES.put(Material.SHEEP_SPAWN_EGG,               5.0);
        PRICES.put(Material.CHICKEN_SPAWN_EGG,             5.0);
        PRICES.put(Material.HORSE_SPAWN_EGG,              15.0);
        PRICES.put(Material.DONKEY_SPAWN_EGG,             10.0);
        PRICES.put(Material.MULE_SPAWN_EGG,               10.0);
        PRICES.put(Material.RABBIT_SPAWN_EGG,              5.0);
        PRICES.put(Material.FOX_SPAWN_EGG,                10.0);
        PRICES.put(Material.WOLF_SPAWN_EGG,               10.0);
        PRICES.put(Material.CAT_SPAWN_EGG,                10.0);
        PRICES.put(Material.OCELOT_SPAWN_EGG,             10.0);
        PRICES.put(Material.PANDA_SPAWN_EGG,              15.0);
        PRICES.put(Material.POLAR_BEAR_SPAWN_EGG,         15.0);
        PRICES.put(Material.AXOLOTL_SPAWN_EGG,            20.0);
        PRICES.put(Material.GOAT_SPAWN_EGG,               10.0);
        PRICES.put(Material.FROG_SPAWN_EGG,                8.0);
        PRICES.put(Material.ALLAY_SPAWN_EGG,              50.0);
        PRICES.put(Material.CAMEL_SPAWN_EGG,              15.0);
        PRICES.put(Material.SNIFFER_SPAWN_EGG,            40.0);
        PRICES.put(Material.TURTLE_SPAWN_EGG,             10.0);
        PRICES.put(Material.SQUID_SPAWN_EGG,               5.0);
        PRICES.put(Material.GLOW_SQUID_SPAWN_EGG,         10.0);
        PRICES.put(Material.BAT_SPAWN_EGG,                 3.0);
        PRICES.put(Material.BEE_SPAWN_EGG,                10.0);
        PRICES.put(Material.VILLAGER_SPAWN_EGG,           30.0);
        PRICES.put(Material.WANDERING_TRADER_SPAWN_EGG,   25.0);
        PRICES.put(Material.IRON_GOLEM_SPAWN_EGG,         40.0);
        PRICES.put(Material.SNOW_GOLEM_SPAWN_EGG,          5.0);
        PRICES.put(Material.MOOSHROOM_SPAWN_EGG,          20.0);
        PRICES.put(Material.PARROT_SPAWN_EGG,             10.0);
        PRICES.put(Material.LLAMA_SPAWN_EGG,              10.0);
        PRICES.put(Material.TRADER_LLAMA_SPAWN_EGG,       10.0);
        PRICES.put(Material.DOLPHIN_SPAWN_EGG,            12.0);
        PRICES.put(Material.COD_SPAWN_EGG,                 3.0);
        PRICES.put(Material.SALMON_SPAWN_EGG,              3.0);
        PRICES.put(Material.TROPICAL_FISH_SPAWN_EGG,       5.0);
        PRICES.put(Material.PUFFERFISH_SPAWN_EGG,          8.0);

        // ── Food / crops ──────────────────────────────────────────────────────
        PRICES.put(Material.BREAD,                         0.3);
        PRICES.put(Material.WHEAT,                         0.1);
        PRICES.put(Material.CARROT,                        0.1);
        PRICES.put(Material.POTATO,                        0.1);
        PRICES.put(Material.BAKED_POTATO,                  0.2);
        PRICES.put(Material.POISONOUS_POTATO,              0.1);
        PRICES.put(Material.COOKED_BEEF,                   0.4);
        PRICES.put(Material.BEEF,                          0.3);
        PRICES.put(Material.PORKCHOP,                      0.3);
        PRICES.put(Material.COOKED_PORKCHOP,               0.4);
        PRICES.put(Material.CHICKEN,                       0.2);
        PRICES.put(Material.COOKED_CHICKEN,                0.3);
        PRICES.put(Material.MUTTON,                        0.2);
        PRICES.put(Material.COOKED_MUTTON,                 0.3);
        PRICES.put(Material.RABBIT,                        0.3);
        PRICES.put(Material.COOKED_RABBIT,                 0.4);
        PRICES.put(Material.COD,                           0.2);
        PRICES.put(Material.COOKED_COD,                    0.3);
        PRICES.put(Material.SALMON,                        0.2);
        PRICES.put(Material.COOKED_SALMON,                 0.3);
        PRICES.put(Material.TROPICAL_FISH,                 0.3);
        PRICES.put(Material.PUFFERFISH,                    0.5);
        PRICES.put(Material.SUGAR_CANE,                    0.1);
        PRICES.put(Material.PUMPKIN,                       0.2);
        PRICES.put(Material.PUMPKIN_PIE,                   0.5);
        PRICES.put(Material.MELON,                         0.1);
        PRICES.put(Material.MELON_SLICE,                   0.1);
        PRICES.put(Material.APPLE,                         0.2);
        PRICES.put(Material.SWEET_BERRIES,                 0.2);
        PRICES.put(Material.GLOW_BERRIES,                  0.3);
        PRICES.put(Material.COCOA_BEANS,                   0.2);
        PRICES.put(Material.COOKIE,                        0.1);
        PRICES.put(Material.CAKE,                          2.0);
        PRICES.put(Material.HONEY_BOTTLE,                  1.5);
        PRICES.put(Material.HONEYCOMB,                     1.0);
        PRICES.put(Material.KELP,                          0.1);
        PRICES.put(Material.DRIED_KELP,                    0.2);
        PRICES.put(Material.DRIED_KELP_BLOCK,              1.8);
        PRICES.put(Material.BEETROOT,                      0.1);
        PRICES.put(Material.BEETROOT_SOUP,                 0.5);
        PRICES.put(Material.MUSHROOM_STEW,                 0.5);
        PRICES.put(Material.RABBIT_STEW,                   1.0);
        PRICES.put(Material.SUSPICIOUS_STEW,               1.5);

        // ── Wood / building basics ────────────────────────────────────────────
        PRICES.put(Material.OAK_LOG,                       0.2);
        PRICES.put(Material.SPRUCE_LOG,                    0.2);
        PRICES.put(Material.BIRCH_LOG,                     0.2);
        PRICES.put(Material.JUNGLE_LOG,                    0.2);
        PRICES.put(Material.ACACIA_LOG,                    0.2);
        PRICES.put(Material.DARK_OAK_LOG,                  0.2);
        PRICES.put(Material.MANGROVE_LOG,                  0.2);
        PRICES.put(Material.CHERRY_LOG,                    0.3);
        PRICES.put(Material.OAK_PLANKS,                    0.1);
        PRICES.put(Material.SPRUCE_PLANKS,                 0.1);
        PRICES.put(Material.BIRCH_PLANKS,                  0.1);
        PRICES.put(Material.JUNGLE_PLANKS,                 0.1);
        PRICES.put(Material.ACACIA_PLANKS,                 0.1);
        PRICES.put(Material.DARK_OAK_PLANKS,               0.1);
        PRICES.put(Material.MANGROVE_PLANKS,               0.1);
        PRICES.put(Material.CHERRY_PLANKS,                 0.1);
        PRICES.put(Material.BAMBOO_PLANKS,                 0.1);
        PRICES.put(Material.COBBLESTONE,                   0.05);
        PRICES.put(Material.STONE,                         0.1);
        PRICES.put(Material.SMOOTH_STONE,                  0.15);
        PRICES.put(Material.STONE_BRICKS,                  0.15);
        PRICES.put(Material.MOSSY_STONE_BRICKS,            0.2);
        PRICES.put(Material.CRACKED_STONE_BRICKS,          0.1);
        PRICES.put(Material.CHISELED_STONE_BRICKS,         0.2);
        PRICES.put(Material.DEEPSLATE,                     0.1);
        PRICES.put(Material.COBBLED_DEEPSLATE,             0.1);
        PRICES.put(Material.DEEPSLATE_BRICKS,              0.2);
        PRICES.put(Material.DEEPSLATE_TILES,               0.25);
        PRICES.put(Material.OBSIDIAN,                      3.0);
        PRICES.put(Material.GRAVEL,                        0.05);
        PRICES.put(Material.SAND,                          0.05);
        PRICES.put(Material.RED_SAND,                      0.08);
        PRICES.put(Material.SANDSTONE,                     0.1);
        PRICES.put(Material.RED_SANDSTONE,                 0.1);
        PRICES.put(Material.CLAY_BALL,                     0.2);
        PRICES.put(Material.CLAY,                          0.8);
        PRICES.put(Material.TERRACOTTA,                    0.2);
        PRICES.put(Material.GLASS,                         0.3);
        PRICES.put(Material.GLASS_PANE,                    0.1);
        PRICES.put(Material.ICE,                           0.3);
        PRICES.put(Material.PACKED_ICE,                    1.0);
        PRICES.put(Material.BLUE_ICE,                      3.0);
        PRICES.put(Material.SNOW_BLOCK,                    0.1);
        PRICES.put(Material.SNOW,                          0.05);
        PRICES.put(Material.PRISMARINE,                    1.0);
        PRICES.put(Material.PRISMARINE_BRICKS,             1.5);
        PRICES.put(Material.DARK_PRISMARINE,               1.5);
        PRICES.put(Material.PRISMARINE_CRYSTALS,           2.0);
        PRICES.put(Material.PRISMARINE_SHARD,              1.5);
        PRICES.put(Material.SEA_LANTERN,                   5.0);
        PRICES.put(Material.SPONGE,                       10.0);
        PRICES.put(Material.WET_SPONGE,                    8.0);
        PRICES.put(Material.BRICKS,                        0.3);
        PRICES.put(Material.NETHER_BRICKS,                 0.5);
        PRICES.put(Material.QUARTZ_BRICKS,                 0.5);
        PRICES.put(Material.DIORITE,                       0.05);
        PRICES.put(Material.ANDESITE,                      0.05);
        PRICES.put(Material.GRANITE,                       0.05);
        PRICES.put(Material.POLISHED_DIORITE,              0.1);
        PRICES.put(Material.POLISHED_ANDESITE,             0.1);
        PRICES.put(Material.POLISHED_GRANITE,              0.1);
        PRICES.put(Material.CALCITE,                       0.1);
        PRICES.put(Material.TUFF,                          0.05);
        PRICES.put(Material.DRIPSTONE_BLOCK,               0.2);
        PRICES.put(Material.POINTED_DRIPSTONE,             0.3);
        PRICES.put(Material.MOSS_BLOCK,                    0.3);
        PRICES.put(Material.ROOTED_DIRT,                   0.1);
        PRICES.put(Material.MUD,                           0.1);
        PRICES.put(Material.PACKED_MUD,                    0.2);
        PRICES.put(Material.MUD_BRICKS,                    0.3);
        PRICES.put(Material.BAMBOO,                        0.1);
        PRICES.put(Material.BAMBOO_BLOCK,                  0.8);
        PRICES.put(Material.SCAFFOLDING,                   0.5);

        // ── Dyes / colors ─────────────────────────────────────────────────────
        PRICES.put(Material.WHITE_DYE,                     0.3);
        PRICES.put(Material.ORANGE_DYE,                    0.3);
        PRICES.put(Material.MAGENTA_DYE,                   0.3);
        PRICES.put(Material.LIGHT_BLUE_DYE,                0.3);
        PRICES.put(Material.YELLOW_DYE,                    0.3);
        PRICES.put(Material.LIME_DYE,                      0.3);
        PRICES.put(Material.PINK_DYE,                      0.3);
        PRICES.put(Material.GRAY_DYE,                      0.3);
        PRICES.put(Material.LIGHT_GRAY_DYE,                0.3);
        PRICES.put(Material.CYAN_DYE,                      0.3);
        PRICES.put(Material.PURPLE_DYE,                    0.3);
        PRICES.put(Material.BLUE_DYE,                      0.5);
        PRICES.put(Material.BROWN_DYE,                     0.5);
        PRICES.put(Material.GREEN_DYE,                     0.3);
        PRICES.put(Material.RED_DYE,                       0.3);
        PRICES.put(Material.BLACK_DYE,                     0.5);
        PRICES.put(Material.INK_SAC,                       0.5);
        PRICES.put(Material.GLOW_INK_SAC,                  2.0);
        PRICES.put(Material.LAPIS_LAZULI,                  2.0);
        PRICES.put(Material.COCOA_BEANS,                   0.2);
        PRICES.put(Material.BONE_MEAL,                     0.2);

        // ── Wool / carpet / concrete ──────────────────────────────────────────
        for (Material m : new Material[]{
            Material.WHITE_WOOL,       Material.ORANGE_WOOL,    Material.MAGENTA_WOOL,
            Material.LIGHT_BLUE_WOOL,  Material.YELLOW_WOOL,    Material.LIME_WOOL,
            Material.PINK_WOOL,        Material.GRAY_WOOL,      Material.LIGHT_GRAY_WOOL,
            Material.CYAN_WOOL,        Material.PURPLE_WOOL,    Material.BLUE_WOOL,
            Material.BROWN_WOOL,       Material.GREEN_WOOL,     Material.RED_WOOL,
            Material.BLACK_WOOL
        }) PRICES.put(m, 0.5);

        for (Material m : new Material[]{
            Material.WHITE_CONCRETE,       Material.ORANGE_CONCRETE,    Material.MAGENTA_CONCRETE,
            Material.LIGHT_BLUE_CONCRETE,  Material.YELLOW_CONCRETE,    Material.LIME_CONCRETE,
            Material.PINK_CONCRETE,        Material.GRAY_CONCRETE,      Material.LIGHT_GRAY_CONCRETE,
            Material.CYAN_CONCRETE,        Material.PURPLE_CONCRETE,    Material.BLUE_CONCRETE,
            Material.BROWN_CONCRETE,       Material.GREEN_CONCRETE,     Material.RED_CONCRETE,
            Material.BLACK_CONCRETE
        }) PRICES.put(m, 0.3);

        // ── Mob drops ─────────────────────────────────────────────────────────
        PRICES.put(Material.BONE,                          0.3);
        PRICES.put(Material.BONE_BLOCK,                    2.7);
        PRICES.put(Material.FEATHER,                       0.2);
        PRICES.put(Material.ARROW,                         0.3);
        PRICES.put(Material.STRING,                        0.2);
        PRICES.put(Material.SPIDER_EYE,                    0.5);
        PRICES.put(Material.ROTTEN_FLESH,                  0.1);
        PRICES.put(Material.GUNPOWDER,                     1.0);
        PRICES.put(Material.SLIME_BALL,                    2.0);
        PRICES.put(Material.SLIME_BLOCK,                  18.0);
        PRICES.put(Material.LEATHER,                       1.0);
        PRICES.put(Material.RABBIT_HIDE,                   0.5);
        PRICES.put(Material.SCUTE,                         8.0);
        PRICES.put(Material.TURTLE_EGG,                   10.0);
        PRICES.put(Material.EGG,                           0.1);

        // ── Archaeology / 1.20 items ──────────────────────────────────────────
        PRICES.put(Material.BRUSH,                         3.0);
        PRICES.put(Material.SUSPICIOUS_SAND,               0.5);
        PRICES.put(Material.SUSPICIOUS_GRAVEL,             0.5);
        PRICES.put(Material.TORCHFLOWER_SEEDS,             5.0);
        PRICES.put(Material.PITCHER_POD,                   5.0);
        PRICES.put(Material.TORCHFLOWER,                  10.0);
        PRICES.put(Material.PITCHER_PLANT,                10.0);
        PRICES.put(Material.SNIFFER_EGG,                  40.0);
    }

    // ── Enchant bonus ─────────────────────────────────────────────────────────
    // Applied on top of the base item price when the item has enchants.
    // Value = additional $ per enchant level summed across all enchants.
    private static final Map<Enchantment, Double> ENCHANT_BONUS = new HashMap<>();
    static {
        ENCHANT_BONUS.put(Enchantment.DAMAGE_ALL,            30.0);
        ENCHANT_BONUS.put(Enchantment.DAMAGE_UNDEAD,                20.0);
        ENCHANT_BONUS.put(Enchantment.DAMAGE_ARTHROPODS,   15.0);
        ENCHANT_BONUS.put(Enchantment.KNOCKBACK,            10.0);
        ENCHANT_BONUS.put(Enchantment.FIRE_ASPECT,          20.0);
        ENCHANT_BONUS.put(Enchantment.LOOT_BONUS_MOBS,              40.0);
        ENCHANT_BONUS.put(Enchantment.SWEEPING_EDGE,        20.0);
        ENCHANT_BONUS.put(Enchantment.DIG_SPEED,           25.0);
        ENCHANT_BONUS.put(Enchantment.SILK_TOUCH,          150.0); // flat per presence
        ENCHANT_BONUS.put(Enchantment.DURABILITY,           20.0);
        ENCHANT_BONUS.put(Enchantment.LOOT_BONUS_BLOCKS,              60.0);
        ENCHANT_BONUS.put(Enchantment.ARROW_DAMAGE,                25.0);
        ENCHANT_BONUS.put(Enchantment.ARROW_KNOCKBACK,                15.0);
        ENCHANT_BONUS.put(Enchantment.ARROW_FIRE,                20.0);
        ENCHANT_BONUS.put(Enchantment.ARROW_INFINITE,            100.0);
        ENCHANT_BONUS.put(Enchantment.PROTECTION_ENVIRONMENTAL,           20.0);
        ENCHANT_BONUS.put(Enchantment.PROTECTION_FIRE,      15.0);
        ENCHANT_BONUS.put(Enchantment.PROTECTION_EXPLOSIONS,     15.0);
        ENCHANT_BONUS.put(Enchantment.PROTECTION_PROJECTILE,15.0);
        ENCHANT_BONUS.put(Enchantment.THORNS,               25.0);
        ENCHANT_BONUS.put(Enchantment.PROTECTION_FALL,      30.0);
        ENCHANT_BONUS.put(Enchantment.OXYGEN,          20.0);
        ENCHANT_BONUS.put(Enchantment.WATER_WORKER,        30.0);
        ENCHANT_BONUS.put(Enchantment.DEPTH_STRIDER,        25.0);
        ENCHANT_BONUS.put(Enchantment.FROST_WALKER,         20.0);
        ENCHANT_BONUS.put(Enchantment.SOUL_SPEED,           30.0);
        ENCHANT_BONUS.put(Enchantment.SWIFT_SNEAK,          40.0);
        ENCHANT_BONUS.put(Enchantment.MENDING,             200.0); // flat per presence
        ENCHANT_BONUS.put(Enchantment.VANISHING_CURSE,       0.0);
        ENCHANT_BONUS.put(Enchantment.BINDING_CURSE,         0.0);
        ENCHANT_BONUS.put(Enchantment.LOYALTY,              20.0);
        ENCHANT_BONUS.put(Enchantment.IMPALING,             25.0);
        ENCHANT_BONUS.put(Enchantment.RIPTIDE,              30.0);
        ENCHANT_BONUS.put(Enchantment.CHANNELING,           40.0);
        ENCHANT_BONUS.put(Enchantment.MULTISHOT,            30.0);
        ENCHANT_BONUS.put(Enchantment.QUICK_CHARGE,         15.0);
        ENCHANT_BONUS.put(Enchantment.PIERCING,             20.0);
    }
    // ─────────────────────────────────────────────────────────────────────────

    public SellManager(DonutSMPPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    public void sellHand(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) {
            player.sendMessage(ChatColor.RED + "Hold an item to sell it.");
            return;
        }
        if (ElytraManager.isSoulbound(item)) {
            player.sendMessage(ChatColor.RED + "Your starter elytra is soulbound and cannot be sold.");
            return;
        }
        if (KitItems.isNoSell(item)) {
            player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.RED
                    + "Free starter / free-shop items cannot be sold.");
            return;
        }
        double price = calcPrice(item);
        if (price <= 0) {
            player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.RED
                    + friendlyName(item.getType()) + " cannot be sold.");
            return;
        }
        double earned = price * item.getAmount();
        addBalance(player.getUniqueId(), earned);
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN
                + "Sold " + item.getAmount() + "x " + friendlyName(item.getType())
                + " for " + ChatColor.YELLOW + "$" + fmt(earned)
                + ChatColor.GRAY + "  (Balance: $" + fmt(getBalance(player.getUniqueId())) + ")");
    }

    public void sellAll(Player player) {
        double totalEarned = 0;
        int totalSold = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() == Material.AIR) continue;
            if (ElytraManager.isSoulbound(item)) continue;  // never sell the starter elytra
            if (KitItems.isNoSell(item)) continue;           // never sell free kit / free-shop items
            double price = calcPrice(item);
            if (price <= 0) continue;
            totalEarned += price * item.getAmount();
            totalSold   += item.getAmount();
            item.setAmount(0);
        }
        if (totalSold == 0) {
            player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.YELLOW
                    + "Nothing sellable in your inventory.");
            return;
        }
        addBalance(player.getUniqueId(), totalEarned);
        player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN
                + "Sold " + totalSold + " items for " + ChatColor.YELLOW + "$" + fmt(totalEarned)
                + ChatColor.GRAY + "  (Balance: $" + fmt(getBalance(player.getUniqueId())) + ")");
    }

    public void showBalance(Player player) {
        player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.WHITE
                + "Balance: " + ChatColor.GREEN + "$" + fmt(getBalance(player.getUniqueId())));
    }

    // ── Price calculation ─────────────────────────────────────────────────────

    /**
     * Base price of the material + per-enchant-level bonus.
     * Returns the price for ONE item (multiply by amount for stacks).
     */
    public double calcPrice(ItemStack item) {
        double base = PRICES.getOrDefault(item.getType(), -1.0);
        if (base < 0) return -1.0;

        // Add enchant bonuses
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                for (Map.Entry<Enchantment, Integer> e : meta.getEnchants().entrySet()) {
                    double bonus = ENCHANT_BONUS.getOrDefault(e.getKey(), 10.0);
                    // Silk touch / Mending are flat (not per-level)
                    if (e.getKey().equals(Enchantment.SILK_TOUCH) ||
                        e.getKey().equals(Enchantment.MENDING)    ||
                        e.getKey().equals(Enchantment.ARROW_INFINITE)   ||
                        e.getKey().equals(Enchantment.CHANNELING)) {
                        base += bonus;
                    } else {
                        base += bonus * e.getValue();
                    }
                }
            }
        }
        return base;
    }

    // ── Balance helpers ───────────────────────────────────────────────────────

    public double getBalance(UUID uuid)              { return balances.getOrDefault(uuid, 0.0); }
    public void   addBalance(UUID uuid, double amt)  { balances.merge(uuid, amt, Double::sum); }
    public boolean deductBalance(UUID uuid, double amt) {
        if (getBalance(uuid) < amt) return false;
        balances.merge(uuid, -amt, Double::sum);
        return true;
    }

    public static Map<Material, Double> getPrices() { return Collections.unmodifiableMap(PRICES); }

    private static String fmt(double v)        { return String.format("%,.2f", v); }
    private static String friendlyName(Material m) { return m.name().toLowerCase().replace("_", " "); }
}
