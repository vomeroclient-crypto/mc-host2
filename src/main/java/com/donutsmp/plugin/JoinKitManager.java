package com.donutsmp.plugin;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Gives every new player a starter kit on their very first join.
 *
 * Kit contents:
 *   - Elytra (soulbound)
 *   - Drill Pickaxe  (Netherite, 3x1 wide-mine, Efficiency V, Fortune III, Unbreaking III, Mending)
 *   - Diamond Shovel (Efficiency V, Unbreaking III, Mending)
 *   - 1x Starter Trident  (Riptide III, Loyalty III, Mending, Unbreakable)
 *   - Diamond Helmet     (Protection IV, Unbreaking III, Mending, Respiration III, Aqua Affinity)
 *   - Diamond Chestplate (Protection IV, Unbreaking III, Mending, Thorns III)
 *   - Diamond Leggings   (Protection IV, Unbreaking III, Mending, Swift Sneak III)
 *   - Diamond Boots      (Protection IV, Unbreaking III, Mending, Feather Falling IV, Depth Strider III)
 *
 * Who has already received the kit is tracked in plugins/DonutSMPWorld/kit_given.yml.
 * Keep-inventory-on-death is handled by KeepInventoryListener (config: keep-inventory-on-death).
 */
public class JoinKitManager implements Listener {

    private final DonutSMPPlugin    plugin;
    private final File              kitFile;
    private final YamlConfiguration kitConfig;
    private final ElytraManager     elytraManager;

    public JoinKitManager(DonutSMPPlugin plugin, ElytraManager elytraManager) {
        this.plugin        = plugin;
        this.elytraManager = elytraManager;
        this.kitFile       = new File(plugin.getDataFolder(), "kit_given.yml");
        if (!kitFile.exists()) {
            try { kitFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        this.kitConfig = YamlConfiguration.loadConfiguration(kitFile);
    }

    // ── Event: first join ──────────────────────────────────────────────────────
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!hasReceivedKit(player.getUniqueId())) {
            giveKit(player);
            markReceived(player.getUniqueId());
            player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN
                    + "Welcome! You've been given your starter kit.");
        } else if (!hasReceivedTrident(player.getUniqueId())) {
            // Existing players who had the old dual-trident kit get the new single trident once.
            giveTrident(player);
            player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN
                    + "Your kit has been updated — Drill Pickaxe & Starter Trident added!");
        }
    }

    // ── Kit builder ────────────────────────────────────────────────────────────
    public void giveKit(Player player) {
        PlayerInventory inv = player.getInventory();

        // Soulbound Elytra (undroppable, unsellable, never breaks)
        ItemStack elytra = elytraManager.createSoulboundElytra();

        // Drill Pickaxe — 3x1 wide-mine, Netherite
        ItemStack pick = KitItems.drillPickaxe();

        // Diamond Shovel
        ItemStack shovel = new ItemStack(Material.DIAMOND_SHOVEL);
        addEnchant(shovel, Enchantment.DIG_SPEED, 5);
        addEnchant(shovel, Enchantment.DURABILITY,  3);
        addEnchant(shovel, Enchantment.MENDING,     1);

        // Armor — full diamond, fully enchanted
        ItemStack helmet = new ItemStack(Material.DIAMOND_HELMET);
        addEnchant(helmet, Enchantment.PROTECTION_ENVIRONMENTAL,         4);
        addEnchant(helmet, Enchantment.DURABILITY,         3);
        addEnchant(helmet, Enchantment.MENDING,            1);
        addEnchant(helmet, Enchantment.OXYGEN,        3);
        addEnchant(helmet, Enchantment.WATER_WORKER,      1);

        ItemStack chest = new ItemStack(Material.DIAMOND_CHESTPLATE);
        addEnchant(chest, Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        addEnchant(chest, Enchantment.DURABILITY,  3);
        addEnchant(chest, Enchantment.MENDING,     1);
        addEnchant(chest, Enchantment.THORNS,      3);

        ItemStack legs = new ItemStack(Material.DIAMOND_LEGGINGS);
        addEnchant(legs, Enchantment.PROTECTION_ENVIRONMENTAL,   4);
        addEnchant(legs, Enchantment.DURABILITY,   3);
        addEnchant(legs, Enchantment.MENDING,      1);
        addEnchant(legs, Enchantment.SWIFT_SNEAK,  3);

        ItemStack boots = new ItemStack(Material.DIAMOND_BOOTS);
        addEnchant(boots, Enchantment.PROTECTION_ENVIRONMENTAL,      4);
        addEnchant(boots, Enchantment.DURABILITY,      3);
        addEnchant(boots, Enchantment.MENDING,         1);
        addEnchant(boots, Enchantment.PROTECTION_FALL, 4);
        addEnchant(boots, Enchantment.DEPTH_STRIDER,   3);

        // Equip armor directly into armor slots
        inv.setHelmet(helmet);
        inv.setChestplate(elytra);   // wear elytra; chestplate goes to inventory
        inv.setLeggings(legs);
        inv.setBoots(boots);

        // Hotbar
        inv.setItem(0, pick);
        inv.setItem(1, shovel);
        inv.addItem(chest);          // diamond chestplate sits in inventory

        // Single starter trident
        giveTrident(player);

        plugin.getLogger().info("Gave starter kit to " + player.getName());
    }

    // ── Trident (single) ──────────────────────────────────────────────────────
    /** Gives one starter trident. Drops leftover naturally if inventory is full. */
    private void giveTrident(Player player) {
        var leftover = player.getInventory().addItem(KitItems.starterTrident());
        leftover.values().forEach(drop ->
                player.getWorld().dropItemNaturally(player.getLocation(), drop));
        markTridentGiven(player.getUniqueId());
    }

    private boolean hasReceivedTrident(UUID uuid) {
        return kitConfig.getBoolean("trident." + uuid, false);
    }

    private void markTridentGiven(UUID uuid) {
        kitConfig.set("trident." + uuid, true);
        // Also clear the old dual-trident key so there's no confusion
        kitConfig.set("tridents." + uuid, true);
        try { kitConfig.save(kitFile); } catch (IOException e) { e.printStackTrace(); }
    }

    // ── Persistence ───────────────────────────────────────────────────────────
    public boolean hasReceivedKit(UUID uuid) {
        return kitConfig.getBoolean(uuid.toString(), false);
    }

    private void markReceived(UUID uuid) {
        kitConfig.set(uuid.toString(), true);
        try { kitConfig.save(kitFile); } catch (IOException e) { e.printStackTrace(); }
    }

    /** Admin override: re-give the kit to any player (bypasses the first-join check). */
    public void forceGiveKit(Player player) {
        giveKit(player);
        markReceived(player.getUniqueId());
        player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN + "Starter kit given!");
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private static void addEnchant(ItemStack item, Enchantment ench, int level) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.addEnchant(ench, level, true);   // true = bypass level cap
        item.setItemMeta(meta);
    }
}
