package com.donutsmp.plugin;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;

/**
 * Free items (starter tridents, free-shop totems) are tagged "no sell" so they
 * can't be turned into infinite /sell money.
 */
public final class KitItems {

    private static final NamespacedKey KEY_NO_SELL  = new NamespacedKey("donutsmp", "no_sell");
    private static final NamespacedKey KEY_SOULBOUND = new NamespacedKey("donutsmp", "soulbound");

    private KitItems() {}

    /** Mark an item as unsellable. */
    public static ItemStack markNoSell(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.getPersistentDataContainer().set(KEY_NO_SELL, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isNoSell(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(KEY_NO_SELL, PersistentDataType.BYTE);
    }

    /**
     * Drill Pickaxe — 3x1 wide mining pickaxe, DonutSMP-style.
     * Efficiency V, Fortune III, Unbreaking III, Mending. Unbreakable, soulbound, no-sell.
     * The 3x1 wide-mine behaviour is handled by DrillPickaxeListener at runtime;
     * this PDC tag is what that listener keys off.
     */
    public static ItemStack drillPickaxe() {
        ItemStack pick = new ItemStack(Material.NETHERITE_PICKAXE);
        ItemMeta meta = pick.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Drill Pickaxe");
        meta.setUnbreakable(true);
        meta.addEnchant(Enchantment.DIG_SPEED,  5, true);
        meta.addEnchant(Enchantment.LOOT_BONUS_BLOCKS,     3, true);
        meta.addEnchant(Enchantment.DURABILITY,  3, true);
        meta.addEnchant(Enchantment.MENDING,     1, true);
        meta.setLore(Arrays.asList(
                ChatColor.YELLOW + "Mines a 3x1 tunnel",
                ChatColor.GRAY   + "Starter item — cannot be sold."
        ));
        // Tag it so DrillPickaxeListener and KeepInventoryListener recognise it
        meta.getPersistentDataContainer().set(KEY_SOULBOUND,  PersistentDataType.BYTE, (byte) 1);
        pick.setItemMeta(meta);
        return markNoSell(pick);
    }

    /** Single starter trident: Riptide III, Loyalty III, Mending, Unbreakable. */
    public static ItemStack starterTrident() {
        ItemStack t = new ItemStack(Material.TRIDENT);
        ItemMeta meta = t.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Starter Trident");
        meta.setUnbreakable(true);
        meta.addEnchant(Enchantment.RIPTIDE,  3, true);
        meta.addEnchant(Enchantment.LOYALTY,  3, true);
        meta.addEnchant(Enchantment.MENDING,  1, true);
        meta.setLore(Arrays.asList(ChatColor.GRAY + "Starter item — cannot be sold."));
        t.setItemMeta(meta);
        return markNoSell(t);
    }

    // Keep old methods as aliases so nothing else in the codebase breaks at compile time.
    /** @deprecated Use {@link #starterTrident()} instead. */
    @Deprecated
    public static ItemStack riptideTrident()  { return starterTrident(); }
    /** @deprecated Removed — only one starter trident is given now. */
    @Deprecated
    public static ItemStack loyaltyTrident()  { return starterTrident(); }
}
