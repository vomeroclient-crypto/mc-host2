package com.donutsmp.plugin;

import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Firework rockets never hurt the player who fired / boosted with them.
 * (Rockets that hit other players or mobs are unaffected.)
 * Covers rockets that already have explosion effects, not just free-shop ones.
 */
public class FireworkSafetyListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Firework firework)) return;   // cheap early exit
        if (!(event.getEntity() instanceof Player victim)) return;
        if (victim.equals(firework.getShooter())) {
            event.setCancelled(true);
        }
    }
}
