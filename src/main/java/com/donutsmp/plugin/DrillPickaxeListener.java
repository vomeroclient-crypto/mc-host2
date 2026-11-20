package com.donutsmp.plugin;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumSet;
import java.util.Set;

/**
 * Implements the Drill Pickaxe's 3x1 wide-mine behaviour.
 *
 * When a player breaks any block with a Drill Pickaxe:
 *   - The broken block is the centre of the 3-wide slice.
 *   - The two adjacent blocks in the perpendicular horizontal direction are
 *     also broken, giving a 3×1 tunnel that always stands two blocks tall
 *     (centre + one above).
 *
 *         [L] [C] [R]   ← mined row   (C = broken by player, L/R = auto-broken)
 *
 * Fortune on the pickaxe applies to all three breaks.
 * Creative-mode players are skipped (vanilla already handles that).
 */
public class DrillPickaxeListener implements Listener {

    private static final NamespacedKey KEY_SOULBOUND = new NamespacedKey("donutsmp", "soulbound");

    /** Block types that are never auto-broken (containers, tile entities, etc.) */
    private static final Set<Material> SKIP_MATERIALS = EnumSet.of(
            Material.CHEST,          Material.TRAPPED_CHEST,
            Material.BARREL,         Material.SHULKER_BOX,
            Material.ENDER_CHEST,    Material.FURNACE,
            Material.BLAST_FURNACE,  Material.SMOKER,
            Material.SPAWNER,        Material.BEDROCK,
            Material.END_PORTAL_FRAME
    );

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player    player = event.getPlayer();
        ItemStack hand   = player.getInventory().getItemInMainHand();

        if (!isDrillPickaxe(hand))             return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Block  centre   = event.getBlock();
        BlockFace facing = player.getFacing();          // which way the player looks

        // Determine the two side-block offsets perpendicular to the player's facing direction
        BlockFace[] sides = perpendicularSides(facing);

        for (BlockFace side : sides) {
            Block sideBlock = centre.getRelative(side);
            if (shouldSkip(sideBlock)) continue;

            // Break silently — no drop duplication, honours Fortune
            sideBlock.breakNaturally(hand);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isDrillPickaxe(ItemStack item) {
        if (item == null || item.getType() != Material.NETHERITE_PICKAXE) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(KEY_SOULBOUND, PersistentDataType.BYTE);
    }

    private static boolean shouldSkip(Block block) {
        return block.getType().isAir()
                || block.getType() == Material.WATER
                || block.getType() == Material.LAVA
                || SKIP_MATERIALS.contains(block.getType());
    }

    /**
     * Returns the two BlockFaces perpendicular to the given facing direction
     * within the horizontal plane (i.e. the "left" and "right" of the tunnel).
     *
     *  NORTH/SOUTH player → tunnel widens EAST–WEST
     *  EAST/WEST   player → tunnel widens NORTH–SOUTH
     *  UP/DOWN     (mining floor/ceiling) → widens EAST–WEST as a sensible default
     */
    private static BlockFace[] perpendicularSides(BlockFace facing) {
        return switch (facing) {
            case NORTH, SOUTH -> new BlockFace[]{ BlockFace.EAST,  BlockFace.WEST  };
            case EAST,  WEST  -> new BlockFace[]{ BlockFace.NORTH, BlockFace.SOUTH };
            default           -> new BlockFace[]{ BlockFace.EAST,  BlockFace.WEST  };
        };
    }
}
