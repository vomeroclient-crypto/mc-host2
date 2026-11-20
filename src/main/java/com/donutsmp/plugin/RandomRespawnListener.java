package com.donutsmp.plugin;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * After dying, players are sent to a random safe spot in the overworld
 * (same search as /rtp) instead of staying at spawn or their bed.
 * Toggle with "random-respawn" in config.yml.
 */
public class RandomRespawnListener implements Listener {

    private final DonutSMPPlugin plugin;
    private final RTPManager rtp;
    private final boolean enabled;

    public RandomRespawnListener(DonutSMPPlugin plugin, RTPManager rtp) {
        this.plugin  = plugin;
        this.rtp     = rtp;
        this.enabled = plugin.getConfig().getBoolean("random-respawn", true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        // Wait 2 ticks so the player is fully respawned before we move them.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) rtp.rtpAfterDeath(player);
        }, 2L);
    }
}
