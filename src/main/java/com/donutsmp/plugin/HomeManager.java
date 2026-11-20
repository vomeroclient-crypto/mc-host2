package com.donutsmp.plugin;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Simple per-player home — /sethome and /home.
 * Homes persist across restarts in plugins/DonutSMPWorld/homes.yml.
 */
public class HomeManager {

    private final DonutSMPPlugin plugin;
    private final File homesFile;
    private final YamlConfiguration homesConfig;

    public HomeManager(DonutSMPPlugin plugin) {
        this.plugin = plugin;
        this.homesFile = new File(plugin.getDataFolder(), "homes.yml");
        if (!homesFile.exists()) {
            try { homesFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        this.homesConfig = YamlConfiguration.loadConfiguration(homesFile);
    }

    /** Save the player's current location as their home. */
    public void setHome(Player player) {
        Location loc = player.getLocation();
        String key = player.getUniqueId().toString();

        homesConfig.set(key + ".world", loc.getWorld().getName());
        homesConfig.set(key + ".x",     loc.getX());
        homesConfig.set(key + ".y",     loc.getY());
        homesConfig.set(key + ".z",     loc.getZ());
        homesConfig.set(key + ".yaw",   (double) loc.getYaw());
        homesConfig.set(key + ".pitch", (double) loc.getPitch());
        save();

        player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN + "Home set!");
    }

    /** Teleport the player to their saved home. */
    public boolean goHome(Player player) {
        String key = player.getUniqueId().toString();
        if (!homesConfig.contains(key + ".world")) {
            player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.RED
                    + "You haven't set a home yet. Use /sethome first.");
            return false;
        }

        String worldName = homesConfig.getString(key + ".world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(ChatColor.RED + "Home world '" + worldName + "' is not loaded.");
            return false;
        }

        double x     = homesConfig.getDouble(key + ".x");
        double y     = homesConfig.getDouble(key + ".y");
        double z     = homesConfig.getDouble(key + ".z");
        float  yaw   = (float) homesConfig.getDouble(key + ".yaw");
        float  pitch = (float) homesConfig.getDouble(key + ".pitch");

        Location dest = new Location(world, x, y, z, yaw, pitch);
        player.teleport(dest);
        player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN + "Teleported home!");
        return true;
    }

    /** Delete a player's home (called on leave or admin reset). */
    public void deleteHome(UUID uuid) {
        homesConfig.set(uuid.toString(), null);
        save();
    }

    private void save() {
        try { homesConfig.save(homesFile); } catch (IOException e) { e.printStackTrace(); }
    }
}
