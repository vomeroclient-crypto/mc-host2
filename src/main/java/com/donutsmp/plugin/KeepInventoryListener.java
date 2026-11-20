package com.donutsmp.plugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Every item in a player's inventory (hotbar, main inventory, armor, offhand)
 * stays with them when they die. Toggle with "keep-inventory-on-death" in config.yml.
 */
public class KeepInventoryListener implements Listener {

    private final boolean enabled;

    public KeepInventoryListener(DonutSMPPlugin plugin) {
        this.enabled = plugin.getConfig().getBoolean("keep-inventory-on-death", true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!enabled) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
    }
}
