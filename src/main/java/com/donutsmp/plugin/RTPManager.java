package com.donutsmp.plugin;

import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * /rtp [region] — Random Teleport
 *
 * Regions mirror the DonutSMP world layout:
 *   overworld — general open overworld (500–4000)
 *   wild      — far-out wilderness (4000–9500)
 *   easy / mid / hard — distance bands matching the schematic placement bands
 *   nether, end
 *
 * Admins can register exact no-land zones with /rtpaddexclusion x z r.
 *
 * PERFORMANCE (v7):
 *  - Chunks are loaded with Paper's async chunk API (no main-thread stalls and
 *    no Bukkit world access from a background thread).
 *  - The overworld surface is read from the heightmap (1 lookup) instead of
 *    scanning up to 60 blocks x 3 getBlockAt calls per attempt.
 *  - Max attempts cut from 25 to 8 (each attempt can generate a brand-new chunk).
 *  - Per-player cooldown + one search at a time, so spamming /rtp can't
 *    queue up chunk generation.
 */
public class RTPManager {

    private final DonutSMPPlugin plugin;
    private final Random random = new Random();

    private static final int MAX_ATTEMPTS = 8;

    private final long cooldownMs;
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Set<UUID> searching = new HashSet<>();

    // ── Region definitions ────────────────────────────────────────────────────
    public static class Region {
        public final String id;
        public final String displayName;
        public final Environment env;
        public final int minR;
        public final int maxR;
        public final int minY;
        public final int maxY;

        public Region(String id, String displayName, Environment env,
                      int minR, int maxR, int minY, int maxY) {
            this.id = id; this.displayName = displayName; this.env = env;
            this.minR = minR; this.maxR = maxR;
            this.minY = minY; this.maxY = maxY;
        }
    }

    public static final List<Region> REGIONS = new ArrayList<>();
    static {
        REGIONS.add(new Region("overworld",  "Overworld",       Environment.NORMAL,          500,  4000,   62,  120));
        REGIONS.add(new Region("wild",       "Wild (far out)",  Environment.NORMAL,         4000,  9500,   60,  120));
        REGIONS.add(new Region("easy",       "Easy Zone",       Environment.NORMAL,          500,  2000,   62,  120));
        REGIONS.add(new Region("mid",        "Mid Zone",        Environment.NORMAL,         2000,  4000,   62,  120));
        REGIONS.add(new Region("hard",       "Hard Zone",       Environment.NORMAL,         5000,  9000,   62,  120));
        REGIONS.add(new Region("nether",     "Nether",          Environment.NETHER,          200,  3000,   32,   90));
        REGIONS.add(new Region("end",        "The End",         Environment.THE_END,         200,  2000,    0,  200));
    }

    // { centerX, centerZ, radius } — registered with /rtpaddexclusion
    private final List<int[]> exclusionZones = new ArrayList<>();

    public RTPManager(DonutSMPPlugin plugin) {
        this.plugin = plugin;
        this.cooldownMs = plugin.getConfig().getInt("performance.rtp-cooldown-seconds", 15) * 1000L;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public void rtp(Player player, Region region) {
        start(player, region, false);
    }

    /** Used after death: random overworld spot, ignores and doesn't consume the /rtp cooldown. */
    public void rtpAfterDeath(Player player) {
        start(player, REGIONS.get(0), true);
    }

    private void start(Player player, Region region, boolean bypassCooldown) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (searching.contains(id)) {
            player.sendMessage(ChatColor.RED + "[RTP] Already searching for a spot, hang on...");
            return;
        }
        Long until = cooldownUntil.get(id);
        if (!bypassCooldown && until != null && until > now && !player.hasPermission("donutsmp.admin")) {
            long secs = (until - now + 999) / 1000;
            player.sendMessage(ChatColor.RED + "[RTP] Please wait " + secs + "s before using /rtp again.");
            return;
        }

        World world = getWorld(region.env);
        if (world == null) {
            player.sendMessage(ChatColor.RED + "[RTP] That dimension isn't available on this server.");
            return;
        }

        searching.add(id);
        if (!bypassCooldown) cooldownUntil.put(id, now + cooldownMs);
        player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.YELLOW
                + "Finding a safe location in " + region.displayName + "...");
        attempt(player, region, world, 0);
    }

    public void rtp(Player player) {
        rtp(player, REGIONS.get(0));
    }

    public Optional<Region> getRegion(String id) {
        return REGIONS.stream().filter(r -> r.id.equalsIgnoreCase(id)).findFirst();
    }

    public void addExclusion(int cx, int cz, int radius) {
        exclusionZones.add(new int[]{cx, cz, radius});
    }

    // ── Search loop (one async chunk load per attempt) ────────────────────────

    private void attempt(Player player, Region region, World world, int tries) {
        UUID id = player.getUniqueId();

        if (!player.isOnline()) { searching.remove(id); return; }
        if (tries >= MAX_ATTEMPTS) {
            searching.remove(id);
            cooldownUntil.remove(id);   // failed search shouldn't cost the player their cooldown
            player.sendMessage(ChatColor.RED + "[RTP] Could not find a safe spot. Try again.");
            return;
        }

        // Pick a random point (cheap math only — re-pick a few times if it's in an exclusion zone)
        int x = 0, z = 0;
        boolean ok = false;
        for (int i = 0; i < 10 && !ok; i++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double dist  = region.minR + random.nextDouble() * (region.maxR - region.minR);
            x = (int) (Math.cos(angle) * dist);
            z = (int) (Math.sin(angle) * dist);
            ok = !isInExclusionZone(x, z);
        }
        if (!ok) { attempt(player, region, world, tries + 1); return; }

        final int fx = x, fz = z;
        world.getChunkAtAsync(fx >> 4, fz >> 4).whenComplete((chunk, err) -> runSync(() -> {
            if (err != null || chunk == null) {
                attempt(player, region, world, tries + 1);
                return;
            }
            Location dest = findSurface(world, fx, fz, region);
            if (dest == null) {
                attempt(player, region, world, tries + 1);
                return;
            }
            searching.remove(id);
            if (!player.isOnline()) return;
            player.teleport(dest);
            player.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN
                    + "Teleported to " + region.displayName
                    + " (" + dest.getBlockX() + ", " + dest.getBlockY() + ", " + dest.getBlockZ() + ")");
        }));
    }

    /** Make sure the callback runs on the main server thread. */
    private void runSync(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else plugin.getServer().getScheduler().runTask(plugin, r);
    }

    // ── Surface finding (main thread, chunk already loaded) ───────────────────

    private Location findSurface(World world, int x, int z, Region region) {
        int feetY;
        if (region.env == Environment.NORMAL) {
            int top = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            feetY = top + 1;
            if (feetY < region.minY || feetY > region.maxY) return null;
        } else {
            feetY = scanForFloor(world, x, z, region.minY, region.maxY);
            if (feetY < 0) return null;
        }
        Location loc = new Location(world, x + 0.5, feetY, z + 0.5, 0f, 0f);
        return isSafe(loc) ? loc : null;
    }

    /** Only used for Nether / End where the heightmap isn't useful. */
    private int scanForFloor(World world, int x, int z, int minY, int maxY) {
        for (int y = maxY; y >= minY; y--) {
            if (world.getBlockAt(x, y, z).getType().isSolid()
                    && world.getBlockAt(x, y + 1, z).getType() == Material.AIR
                    && world.getBlockAt(x, y + 2, z).getType() == Material.AIR) {
                return y + 1;
            }
        }
        return -1;
    }

    private boolean isInExclusionZone(int x, int z) {
        for (int[] zone : exclusionZones) {
            int dx = x - zone[0];
            int dz = z - zone[1];
            if (dx * dx + dz * dz <= zone[2] * zone[2]) return true;
        }
        return false;
    }

    private boolean isSafe(Location loc) {
        Material feet  = loc.getBlock().getType();
        Material head  = loc.clone().add(0, 1, 0).getBlock().getType();
        Material below = loc.clone().add(0, -1, 0).getBlock().getType();
        if (!below.isSolid()) return false;
        if (feet != Material.AIR || head != Material.AIR) return false;
        return below != Material.MAGMA_BLOCK
            && below != Material.CACTUS
            && below != Material.CAMPFIRE
            && below != Material.SOUL_CAMPFIRE;
    }

    private World getWorld(Environment env) {
        for (World w : plugin.getServer().getWorlds()) {
            if (w.getEnvironment() == env) return w;
        }
        return null;
    }
}
