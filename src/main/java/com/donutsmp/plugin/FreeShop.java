package com.donutsmp.plugin;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;

/**
 * /freeshop — opens a GUI where players can grab free items.
 *
 * Main menu shows Fireworks, Totems of Undying and Bottle o' Enchanting.
 * Clicking one opens an amount picker (1 / 8 / 16 / 32 / 64; totems don't
 * stack, so their picker offers 1 / 2 / 4 / 8 / 16).
 */
public class FreeShop implements Listener {

    private static final String TITLE_MAIN   = ChatColor.DARK_PURPLE + "✦ Free Shop";
    private static final String TITLE_FW     = ChatColor.DARK_PURPLE + "✦ Fireworks — Pick Amount";
    private static final String TITLE_XP     = ChatColor.DARK_PURPLE + "✦ XP Bottles — Pick Amount";
    private static final String TITLE_TOTEM  = ChatColor.DARK_PURPLE + "✦ Totems — Pick Amount";

    // Amounts offered in the picker
    private static final int[] AMOUNTS       = {1, 8, 16, 32, 64};
    private static final int[] TOTEM_AMOUNTS = {1, 2, 4, 8, 16};   // totems stack to 1

    private final DonutSMPPlugin plugin;

    public FreeShop(DonutSMPPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Open main menu ────────────────────────────────────────────────────────

    public void open(Player player) {
        Inventory gui = Bukkit.createInventory(null, 9, TITLE_MAIN);
        fillGlass(gui);
        gui.setItem(2, makeFireworkDisplay());
        gui.setItem(4, makeTotemDisplay());
        gui.setItem(6, makeXpBottleDisplay());
        player.openInventory(gui);
    }

    // ── Open amount picker ────────────────────────────────────────────────────

    private void openAmountPicker(Player player, String title, Material material) {
        // 9 slots: glass on edges, 5 amount options in slots 2-6
        Inventory gui = Bukkit.createInventory(null, 9, title);
        fillGlass(gui);
        int[] amounts = material == Material.TOTEM_OF_UNDYING ? TOTEM_AMOUNTS : AMOUNTS;
        for (int i = 0; i < amounts.length; i++) {
            gui.setItem(i + 2, makeAmountChoice(material, amounts[i]));
        }
        player.openInventory(gui);
    }

    // ── Click handler ─────────────────────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        String title = event.getView().getTitle();

        if (!title.equals(TITLE_MAIN) && !title.equals(TITLE_FW)
                && !title.equals(TITLE_XP) && !title.equals(TITLE_TOTEM)) return;

        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR
                || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

        // ── Main menu clicks ──────────────────────────────────────────────────
        if (title.equals(TITLE_MAIN)) {
            if (clicked.getType() == Material.FIREWORK_ROCKET) {
                openAmountPicker(player, TITLE_FW, Material.FIREWORK_ROCKET);
            } else if (clicked.getType() == Material.TOTEM_OF_UNDYING) {
                openAmountPicker(player, TITLE_TOTEM, Material.TOTEM_OF_UNDYING);
            } else if (clicked.getType() == Material.EXPERIENCE_BOTTLE) {
                openAmountPicker(player, TITLE_XP, Material.EXPERIENCE_BOTTLE);
            }
            return;
        }

        // ── Amount picker clicks ──────────────────────────────────────────────
        int amount = clicked.getAmount();
        if (title.equals(TITLE_FW)) {
            give(player, buildFirework(amount));
            player.sendMessage(ChatColor.GOLD + "[Free Shop] " + ChatColor.GREEN
                    + "Given " + amount + "x Firework Rocket! 🎆");
        } else if (title.equals(TITLE_TOTEM)) {
            giveTotems(player, amount);
            player.sendMessage(ChatColor.GOLD + "[Free Shop] " + ChatColor.GREEN
                    + "Given " + amount + "x Totem of Undying!");
        } else if (title.equals(TITLE_XP)) {
            give(player, new ItemStack(Material.EXPERIENCE_BOTTLE, amount));
            player.sendMessage(ChatColor.GOLD + "[Free Shop] " + ChatColor.GREEN
                    + "Given " + amount + "x Bottle o' Enchanting! ✨");
        }
        player.closeInventory();
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    /** Display icon shown in the main menu (always 1 item visually). */
    private ItemStack makeFireworkDisplay() {
        ItemStack fw = buildFirework(1);
        ItemMeta meta = fw.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Firework Rockets");
            meta.setLore(Arrays.asList(
                ChatColor.YELLOW + "Click to choose an amount.",
                ChatColor.GRAY   + "Free!"
            ));
            fw.setItemMeta(meta);
        }
        return fw;
    }

    private ItemStack makeTotemDisplay() {
        ItemStack totem = new ItemStack(Material.TOTEM_OF_UNDYING, 1);
        ItemMeta meta = totem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Totem of Undying");
            meta.setLore(Arrays.asList(
                ChatColor.YELLOW + "Click to choose an amount.",
                ChatColor.GRAY   + "Free!"
            ));
            totem.setItemMeta(meta);
        }
        return totem;
    }

    private ItemStack makeXpBottleDisplay() {
        ItemStack bottle = new ItemStack(Material.EXPERIENCE_BOTTLE, 1);
        ItemMeta meta = bottle.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "Bottle o' Enchanting");
            meta.setLore(Arrays.asList(
                ChatColor.YELLOW + "Click to choose an amount.",
                ChatColor.GRAY   + "Free!"
            ));
            bottle.setItemMeta(meta);
        }
        return bottle;
    }

    /** One slot in the amount picker. The item itself IS the thing they'll receive, just in that quantity. */
    private ItemStack makeAmountChoice(Material material, int amount) {
        ItemStack item = material == Material.FIREWORK_ROCKET
                ? buildFirework(amount)
                : new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = material == Material.FIREWORK_ROCKET ? "Firework Rocket"
                        : material == Material.TOTEM_OF_UNDYING ? "Totem of Undying"
                        : "Bottle o' Enchanting";
            meta.setDisplayName(ChatColor.YELLOW + "x" + amount + " " + name);
            meta.setLore(Arrays.asList(ChatColor.GREEN + "Click to claim!"));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Flight-boost firework with NO explosion effect, so using it with an
     * elytra never damages the player (only fireworks with a star deal damage).
     */
    private ItemStack buildFirework(int amount) {
        ItemStack fw = new ItemStack(Material.FIREWORK_ROCKET, amount);
        FireworkMeta meta = (FireworkMeta) fw.getItemMeta();
        if (meta != null) {
            meta.setPower(2);
            fw.setItemMeta(meta);
        }
        return fw;
    }

    private void fillGlass(Inventory gui) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) { meta.setDisplayName(" "); pane.setItemMeta(meta); }
        for (int i = 0; i < gui.getSize(); i++) gui.setItem(i, pane);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Totems don't stack — hand them out one by one, tagged so they can't be sold. */
    private static void giveTotems(Player player, int amount) {
        for (int i = 0; i < amount; i++) {
            give(player, KitItems.markNoSell(new ItemStack(Material.TOTEM_OF_UNDYING, 1)));
        }
    }

    private static void give(Player player, ItemStack item) {
        java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
        leftover.values().forEach(drop ->
                player.getWorld().dropItemNaturally(player.getLocation(), drop));
    }
}
