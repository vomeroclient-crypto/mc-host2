package com.donutsmp.plugin;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * On every respawn:
 *  1. Removes duplicate starter tridents down to exactly 1.
 *  2. Restores any missing kit items (Drill Pickaxe, trident, armor, elytra).
 *
 * "Missing" means: the player has zero of that item tagged as a kit item.
 * Items the player earned themselves are never touched.
 *
 * ElytraManager handles the elytra separately; we skip it here to avoid
 * doubling up.
 */
public class RespawnKitRestorer implements Listener {

    private static final NamespacedKey KEY_NO_SELL  = new NamespacedKey("donutsmp", "no_sell");
    private static final NamespacedKey KEY_SOULBOUND = new NamespacedKey("donutsmp", "soulbound");

    private final DonutSMPPlugin plugin;
    private final ElytraManager  elytraManager;

    public RespawnKitRestorer(DonutSMPPlugin plugin, ElytraManager elytraManager) {
        this.plugin        = plugin;
        this.elytraManager = elytraManager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        // Run one tick after respawn so inventory is fully loaded
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            capTridents(player);
            restoreMissingKit(player);
        }, 1L);
    }

    // ── Trident cap ───────────────────────────────────────────────────────────

    /**
     * Keeps exactly 1 no-sell trident. Any extras are removed silently.
     * Player-earned tridents (no PDC tag) are never touched.
     */
    private void capTridents(Player player) {
        PlayerInventory inv = player.getInventory();
        List<Integer> kitTridentSlots = new ArrayList<>();

        // Check all slots including offhand (slot 40) and armor slots
        for (int i = 0; i < 41; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() == Material.TRIDENT && isKitItem(item)) {
                kitTridentSlots.add(i);
            }
        }

        // Remove extras — keep only the first one found
        for (int i = 1; i < kitTridentSlots.size(); i++) {
            inv.setItem(kitTridentSlots.get(i), null);
        }

        if (kitTridentSlots.size() > 1) {
            player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.YELLOW
                    + "Extra starter tridents removed — you only need 1.");
        }
    }

    // ── Kit restore ───────────────────────────────────────────────────────────

    private void restoreMissingKit(Player player) {
        PlayerInventory inv = player.getInventory();
        List<String> restored = new ArrayList<>();

        // ── Drill Pickaxe ────────────────────────────────────────────────────
        if (!hasDrillPickaxe(inv)) {
            inv.addItem(KitItems.drillPickaxe());
            restored.add("Drill Pickaxe");
        }

        // ── Starter Trident ──────────────────────────────────────────────────
        if (!hasKitTrident(inv)) {
            inv.addItem(KitItems.starterTrident());
            restored.add("Starter Trident");
        }

        // ── Armor ────────────────────────────────────────────────────────────
        if (!hasKitArmor(inv, Material.DIAMOND_HELMET)) {
            ItemStack h = makeHelmet();
            inv.setHelmet(h);
            restored.add("Helmet");
        }
        if (!hasKitArmor(inv, Material.DIAMOND_CHESTPLATE)) {
            ItemStack c = makeChestplate();
            // If elytra is in chestplate slot, put the chestplate in inventory
            if (inv.getChestplate() != null && inv.getChestplate().getType() != Material.AIR
                    && inv.getChestplate().getType() != Material.DIAMOND_CHESTPLATE) {
                inv.addItem(c);
            } else {
                inv.addItem(c);
            }
            restored.add("Chestplate");
        }
        if (!hasKitArmor(inv, Material.DIAMOND_LEGGINGS)) {
            inv.setLeggings(makeLeggings());
            restored.add("Leggings");
        }
        if (!hasKitArmor(inv, Material.DIAMOND_BOOTS)) {
            inv.setBoots(makeBoots());
            restored.add("Boots");
        }

        if (!restored.isEmpty()) {
            player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN
                    + "Restored missing kit items: " + String.join(", ", restored));
        }
    }

    // ── Detection helpers ─────────────────────────────────────────────────────

    private boolean hasDrillPickaxe(PlayerInventory inv) {
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() == Material.NETHERITE_PICKAXE && isDrillPickaxe(item))
                return true;
        }
        return false;
    }

    private boolean hasKitTrident(PlayerInventory inv) {
        for (int i = 0; i < 41; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() == Material.TRIDENT && isKitItem(item))
                return true;
        }
        return false;
    }

    private boolean hasKitArmor(PlayerInventory inv, Material type) {
        // Check armor slots + full inventory (player might have it in a bag slot)
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() == type) return true;
        }
        // Also check armor slots explicitly
        ItemStack[] armor = inv.getArmorContents();
        for (ItemStack item : armor) {
            if (item != null && item.getType() == type) return true;
        }
        return false;
    }

    private static boolean isKitItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(KEY_NO_SELL, PersistentDataType.BYTE);
    }

    private static boolean isDrillPickaxe(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(KEY_SOULBOUND, PersistentDataType.BYTE);
    }

    // ── Armor builders (match JoinKitManager exactly) ─────────────────────────

    private static ItemStack makeHelmet() {
        ItemStack h = new ItemStack(Material.DIAMOND_HELMET);
        addEnchant(h, Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        addEnchant(h, Enchantment.DURABILITY,               3);
        addEnchant(h, Enchantment.MENDING,                  1);
        addEnchant(h, Enchantment.OXYGEN,                   3);
        addEnchant(h, Enchantment.WATER_WORKER,             1);
        return h;
    }

    private static ItemStack makeChestplate() {
        ItemStack c = new ItemStack(Material.DIAMOND_CHESTPLATE);
        addEnchant(c, Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        addEnchant(c, Enchantment.DURABILITY,               3);
        addEnchant(c, Enchantment.MENDING,                  1);
        addEnchant(c, Enchantment.THORNS,                   3);
        return c;
    }

    private static ItemStack makeLeggings() {
        ItemStack l = new ItemStack(Material.DIAMOND_LEGGINGS);
        addEnchant(l, Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        addEnchant(l, Enchantment.DURABILITY,               3);
        addEnchant(l, Enchantment.MENDING,                  1);
        addEnchant(l, Enchantment.SWIFT_SNEAK,              3);
        return l;
    }

    private static ItemStack makeBoots() {
        ItemStack b = new ItemStack(Material.DIAMOND_BOOTS);
        addEnchant(b, Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        addEnchant(b, Enchantment.DURABILITY,               3);
        addEnchant(b, Enchantment.MENDING,                  1);
        addEnchant(b, Enchantment.PROTECTION_FALL,          4);
        addEnchant(b, Enchantment.DEPTH_STRIDER,            3);
        return b;
    }

    private static void addEnchant(ItemStack item, Enchantment ench, int level) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.addEnchant(ench, level, true);
        item.setItemMeta(meta);
    }
}
