package com.donutsmp.plugin;

import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manages the soulbound starter elytra:
 *  - Cannot be dropped
 *  - Cannot be sold (/sell hand or /sell all)
 *  - Can be freely moved around the inventory / unequipped
 *  - Unbreakable (item flag, no listener overhead)
 *  - Returns to the player on death (removed from death drops)
 */
public class ElytraManager implements Listener {

    private static final NamespacedKey KEY_SOULBOUND = new NamespacedKey("donutsmp", "soulbound_elytra");

    private final DonutSMPPlugin plugin;

    public ElytraManager(DonutSMPPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Item factory ──────────────────────────────────────────────────────────

    /** Create the soulbound elytra item. */
    public ItemStack createSoulboundElytra() {
        ItemStack elytra = new ItemStack(Material.ELYTRA);
        ItemMeta meta = elytra.getItemMeta();
        if (meta == null) return elytra;

        // Mark it as soulbound via PersistentDataContainer
        meta.getPersistentDataContainer().set(KEY_SOULBOUND, PersistentDataType.BYTE, (byte) 1);

        // Truly unbreakable — no per-damage listener needed (saves CPU)
        meta.setUnbreakable(true);
        meta.addEnchant(Enchantment.DURABILITY, 3, true);
        meta.addEnchant(Enchantment.MENDING,    1, true);

        // Lore so the player knows it's special
        List<String> lore = new ArrayList<>(Arrays.asList(
            ChatColor.DARK_PURPLE + "✦ Soulbound",
            ChatColor.GRAY        + "Cannot be dropped or sold.",
            ChatColor.GRAY        + "Returns to you on death."
        ));
        meta.setLore(lore);

        elytra.setItemMeta(meta);
        return elytra;
    }

    /** Returns true if the given ItemStack is the soulbound elytra. */
    public static boolean isSoulbound(ItemStack item) {
        if (item == null || item.getType() != Material.ELYTRA) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(
                new NamespacedKey("donutsmp", "soulbound_elytra"), PersistentDataType.BYTE);
    }

    // ── Events ────────────────────────────────────────────────────────────────

    /** Block dropping the elytra from the hotbar (Q key or drag-out). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isSoulbound(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Your starter elytra is soulbound and cannot be dropped.");
        }
    }

    /**
     * Block moving the elytra OUT of the player's inventory into another
     * container (chest, crafting table, etc.).  Moving within the player's
     * own inventory is allowed.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        // Cursor is the item being held on the mouse
        ItemStack cursor  = event.getCursor();
        ItemStack current = event.getCurrentItem();

        // If a soulbound elytra is on the cursor and they're dropping it into
        // an inventory that is NOT their own player inventory, cancel.
        if (isSoulbound(cursor) || isSoulbound(current)) {
            // Allow clicks purely within the player's own inventory view
            // (top inventory = external container; bottom = player inv)
            boolean movingToExternal =
                    event.getView().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING
                    && event.getClickedInventory() != event.getView().getBottomInventory();

            // shift-click from player inv into an external container
            boolean shiftIntoExternal =
                    event.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY
                    && event.getView().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING;

            if (movingToExternal || shiftIntoExternal) {
                event.setCancelled(true);
                ((Player) event.getWhoClicked()).sendMessage(
                        ChatColor.RED + "Your starter elytra is soulbound and cannot be moved out of your inventory.");
            }
        }
    }

    /**
     * Remove the soulbound elytra from death drops so it doesn't appear on
     * the ground.  Then give it back on respawn.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(ElytraManager::isSoulbound);
        // Also remove it from dropped items if it was in the chestplate slot
        // (Paper/Spigot includes armor in getDrops(), so the removeIf above covers it)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        // Check if they still have it somewhere in their inventory (they might
        // have had it in a non-chestplate slot)
        for (ItemStack item : player.getInventory().getContents()) {
            if (isSoulbound(item)) return;  // already have it
        }
        // Also check armor slots
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (isSoulbound(item)) return;
        }
        // Give it back — place in chestplate slot if empty, otherwise inventory
        ItemStack newElytra = createSoulboundElytra();
        if (player.getInventory().getChestplate() == null
                || player.getInventory().getChestplate().getType() == Material.AIR) {
            player.getInventory().setChestplate(newElytra);
        } else {
            player.getInventory().addItem(newElytra);
        }
        player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.AQUA
                + "Your soulbound elytra has returned to you.");
    }
}
