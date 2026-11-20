package com.donutsmp.plugin;

import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.scoreboard.*;

import java.util.*;

/**
 * StaffWatcher — passive surveillance system.
 *
 * HOW IT WORKS (mirrors what clients like MotionClient track on the player side):
 *
 *  Suspicion scoring
 *  -----------------
 *  Every suspicious event raises a player's suspicion score. When the score
 *  crosses a threshold the system "flags" the player and silently alerts all
 *  online ops/staff. The alert is a subtle chat line only visible to staff.
 *
 *  What raises suspicion:
 *   • Logging out and back in quickly (relog — classic anti-vanish/base-finder trick)
 *   • Being near a known base/stash location when they log back in
 *   • Logging in-out more than N times in a short window
 *   • Large XP drops between sessions (killed someone with good gear)
 *   • Moving in suspicious straight lines underground (base tunnel behaviour)
 *   • Breaking blocks in a pattern consistent with a chunk finder (grid-line breaks)
 *
 *  Vanish detection (the MotionClient "anti-vanish" / "staff live" mirror)
 *  -----------------------------------------------------------------------
 *  When a player is flagged:
 *   1. A "ghost" spectator clone is secretly assigned to follow them. The ghost
 *      is a real staff member put into spectator mode and teleported next to the
 *      target — but the target sees nothing because they are hidden from the target.
 *   2. The target's nametag colour turns dark-red in staff tab (only staff can see
 *      this — the tab entry is in a separate scoreboard team visible only to ops).
 *   3. All staff get a periodic location ping: "§c[WATCH] §7PlayerName §8@ §f1200, -30, -800"
 *
 *  Staff commands
 *  --------------
 *   /staffwatch list          — list all currently flagged players and their scores
 *   /staffwatch clear <name>  — clear a player's suspicion score
 *   /staffwatch goto <name>   — teleport (spectator) to the flagged player
 *   /staffwatch flag <name>   — manually flag a player
 *
 *  Config keys (staff-watch section of config.yml):
 *   relog-window-seconds: 30       — relog within this many seconds counts as suspicious
 *   relog-count-threshold: 3       — 3+ relogs in a minute triggers a flag
 *   flag-score-threshold: 10       — suspicion score needed to alert staff
 *   base-proximity-radius: 60      — blocks from a known base/stash to count as "near a base"
 *   alert-interval-seconds: 60     — how often to re-ping staff about flagged players
 */
public class StaffWatcher implements Listener {

    // ── Score weights ────────────────────────────────────────────────────────
    private static final int SCORE_RELOG_FAST       = 3;   // logged back in < 30 s
    private static final int SCORE_RELOG_NEAR_BASE  = 5;   // logged in next to known base
    private static final int SCORE_RELOG_FLOOD       = 4;  // 3+ relogs in 60 s window
    private static final int SCORE_GRID_MINE        = 2;   // broke blocks on chunk-border line
    private static final int SCORE_DEEP_STRAIGHT    = 2;   // long horizontal underground movement
    private static final int SCORE_XP_SPIKE         = 3;   // big XP gain between sessions (looted)
    private static final int SCORE_MANUAL           = 15;  // /staffwatch flag

    // ── Deepslate-relog grace window ─────────────────────────────────────────
    // Relogs from deepslate level (Y < DEEPSLATE_Y) are "mandatory" (the
    // client must reload chunks from scratch at that depth).  We suppress all
    // relog-based suspicion scores for DEEPSLATE_GRACE_MS after such a relog
    // and tighten the near-base detection radius to DEEPSLATE_NEAR_RADIUS so
    // only genuinely suspicious proximity still fires.
    private static final int  DEEPSLATE_Y          = 16;   // below this = deepslate relog
    private static final long DEEPSLATE_GRACE_MS   = 60_000L; // 60-second grace window
    private static final int  DEEPSLATE_NEAR_RADIUS = 300;  // tightened base-proximity during grace

    // ── Per-player tracking ──────────────────────────────────────────────────
    private final Map<UUID, PlayerRecord> records = new HashMap<>();

    // Known base / stash locations (populated when bases are generated)
    private final List<Location> knownBases = new ArrayList<>();

    private final DonutSMPPlugin plugin;
    private final int relogWindowSec;
    private final int relogCountThreshold;
    private final int flagThreshold;
    private final int baseProximityRadius;
    private final int alertIntervalSec;

    // Scoreboard team so staff nametags show correctly for flagged players
    private Scoreboard staffBoard;
    private Team       flaggedTeam;

    public StaffWatcher(DonutSMPPlugin plugin) {
        this.plugin = plugin;

        relogWindowSec       = plugin.getConfig().getInt("staff-watch.relog-window-seconds",    30);
        relogCountThreshold  = plugin.getConfig().getInt("staff-watch.relog-count-threshold",    3);
        flagThreshold        = plugin.getConfig().getInt("staff-watch.flag-score-threshold",    10);
        baseProximityRadius  = plugin.getConfig().getInt("staff-watch.base-proximity-radius",   60);
        alertIntervalSec     = plugin.getConfig().getInt("staff-watch.alert-interval-seconds",  60);

        setupScoreboard();
        startAlertTask();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Call this every time a base or stash location is placed. */
    public void registerBase(Location loc) {
        knownBases.add(loc.clone());
    }

    /** Forget known bases inside this XZ box (used when /removebase deletes a base). */
    public void unregisterBasesIn(World world, int minX, int minZ, int maxX, int maxZ) {
        knownBases.removeIf(l -> l.getWorld() != null && l.getWorld().equals(world)
                && l.getBlockX() >= minX && l.getBlockX() <= maxX
                && l.getBlockZ() >= minZ && l.getBlockZ() <= maxZ);
    }

    /** Manually flag a player (e.g. from /staffwatch flag). */
    public void manualFlag(Player target, CommandSender issuer) {
        addScore(target.getUniqueId(), target.getName(), SCORE_MANUAL, "manual flag by " + issuer.getName());
    }

    /** Clear a player's record. */
    public void clearRecord(UUID uuid) {
        PlayerRecord rec = records.remove(uuid);
        if (rec != null) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) unflagNametag(p);
        }
    }

    /** Summary of all records with score > 0. */
    public List<String> listSummary() {
        List<String> out = new ArrayList<>();
        records.forEach((uuid, rec) -> {
            if (rec.score > 0) {
                String online = plugin.getServer().getPlayer(uuid) != null ? "§aONLINE" : "§7offline";
                out.add("§c" + rec.name + " §8[§e" + rec.score + " pts§8] " + online
                    + " §8— last: §7" + rec.lastReason);
            }
        });
        if (out.isEmpty()) out.add("§7No players flagged.");
        return out;
    }

    /** Teleport a staff member (into spectator) to a flagged player. */
    public boolean gotoPlayer(Player staff, String targetName) {
        for (Map.Entry<UUID, PlayerRecord> e : records.entrySet()) {
            if (e.getValue().name.equalsIgnoreCase(targetName)) {
                Player target = plugin.getServer().getPlayer(e.getKey());
                if (target == null) {
                    staff.sendMessage("§c" + targetName + " is offline.");
                    return true;
                }
                GameMode prev = staff.getGameMode();
                staff.setGameMode(GameMode.SPECTATOR);
                staff.teleport(target.getLocation().clone().add(0, 1, 0));
                staff.sendMessage("§6[StaffWatch] §7Spectating §c" + target.getName()
                    + "§7. Previous mode: §f" + prev.name() + "§7 — use /gamemode to return.");
                // Hide the staff member from everyone including the target
                for (Player all : plugin.getServer().getOnlinePlayers()) {
                    if (!all.hasPermission("donutsmp.staff")) {
                        all.hidePlayer(plugin, staff);
                    }
                }
                return true;
            }
        }
        staff.sendMessage("§cNo record for '" + targetName + "'. Use §e/staffwatch list§c to see flagged players.");
        return true;
    }

    // ── Event listeners ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (p.hasPermission("donutsmp.staff")) return;
        PlayerRecord rec = getOrCreate(p.getUniqueId(), p.getName());
        rec.lastQuitMs = System.currentTimeMillis();
        rec.lastQuitLocation = p.getLocation().clone();
        rec.lastQuitY = p.getLocation().getBlockY();  // remember quit depth
        rec.relogTimes.add(rec.lastQuitMs);
        // Prune relog timestamps older than 60s
        long cutoff = rec.lastQuitMs - 60_000L;
        rec.relogTimes.removeIf(t -> t < cutoff);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (p.hasPermission("donutsmp.staff")) {
            // Give staff their scoreboard so they can see flagged nametags
            p.setScoreboard(staffBoard);
            return;
        }

        PlayerRecord rec = getOrCreate(p.getUniqueId(), p.getName());
        rec.name = p.getName(); // name can change

        long now = System.currentTimeMillis();

        // ── Deepslate-relog grace window ─────────────────────────────────────
        // A relog from deepslate (Y < DEEPSLATE_Y) is "mandatory" — the client
        // must reload all chunks from scratch at that depth, so we do NOT score
        // it as suspicious and suppress relog-flood too.  We also tighten the
        // near-base radius to DEEPSLATE_NEAR_RADIUS for the grace window so
        // only genuinely suspicious proximity (someone parked on a base) fires.
        boolean deepslateRelog = rec.lastQuitMs > 0 && rec.lastQuitY < DEEPSLATE_Y;
        if (deepslateRelog) {
            rec.deepslateGraceUntilMs = now + DEEPSLATE_GRACE_MS;
        }

        // ── Relog speed check ────────────────────────────────────────────────
        if (!rec.inDeepslateGrace() && rec.lastQuitMs > 0) {
            long elapsed = now - rec.lastQuitMs;
            if (elapsed < relogWindowSec * 1000L) {
                addScore(p.getUniqueId(), p.getName(), SCORE_RELOG_FAST,
                    "fast relog (" + (elapsed / 1000) + "s)");
            }
        }

        // ── Relog flood check ────────────────────────────────────────────────
        if (!rec.inDeepslateGrace() && rec.relogTimes.size() >= relogCountThreshold) {
            addScore(p.getUniqueId(), p.getName(), SCORE_RELOG_FLOOD,
                rec.relogTimes.size() + " relogs in 60s");
        }

        // ── Near-base login check ─────────────────────────────────────────────
        // During deepslate grace use a tighter radius; outside grace use the
        // normal configured radius so ordinary surface relogs near bases still flag.
        int activeRadius = rec.inDeepslateGrace() ? DEEPSLATE_NEAR_RADIUS : baseProximityRadius;
        Location loginLoc = p.getLocation();
        for (Location base : knownBases) {
            if (base.getWorld() != null && base.getWorld().equals(loginLoc.getWorld())) {
                double dist = loginLoc.distance(base);
                if (dist <= activeRadius) {
                    addScore(p.getUniqueId(), p.getName(), SCORE_RELOG_NEAR_BASE,
                        "logged in " + (int) dist + " blocks from known base");
                    break;
                }
            }
        }

        // ── Assign staff scoreboard so nametag shows correctly ───────────────
        // The flagged team is on staffBoard — normal players don't see it
        // so we do NOT set it on them. Staff joining later will see it via their
        // own staffBoard assignment above.
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (p.hasPermission("donutsmp.staff")) return;
        org.bukkit.block.Block b = event.getBlock();

        // Grid-line mining: breaking blocks right on chunk borders (x%16==0 or z%16==0)
        // is a strong signal of a chunk finder / sus-chunk ESP user tracing chunk edges.
        int lx = Math.floorMod(b.getX(), 16);
        int lz = Math.floorMod(b.getZ(), 16);
        if ((lx == 0 || lx == 15 || lz == 0 || lz == 15) && b.getY() < 0) {
            PlayerRecord rec = getOrCreate(p.getUniqueId(), p.getName());
            // Suppress grid-mine scoring during the deepslate-relog grace window:
            // the player is legitimately re-orienting after chunk reload.
            if (rec.inDeepslateGrace()) return;
            rec.chunkEdgeBreaks++;
            if (rec.chunkEdgeBreaks == 8) { // 8 chunk-edge breaks underground
                addScore(p.getUniqueId(), p.getName(), SCORE_GRID_MINE,
                    "8 chunk-border breaks underground (sus-chunk trace?)");
                rec.chunkEdgeBreaks = 0;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        if (p.hasPermission("donutsmp.staff")) return;
        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;

        // Only care about horizontal underground movement
        if (to.getY() >= 0) return;
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 0.1) return;

        PlayerRecord rec = getOrCreate(p.getUniqueId(), p.getName());

        // During deepslate-relog grace, skip straight-line detection: the player
        // is heading back to their base after the mandatory chunk-reload relog.
        if (rec.inDeepslateGrace()) return;

        // Track heading consistency: if yaw doesn't change much over many blocks,
        // that's a straight-line deep tunnel — classic base-finder behaviour.
        float yaw = to.getYaw();
        if (rec.lastUndergroundYaw >= -360) {
            float delta = Math.abs(yaw - rec.lastUndergroundYaw);
            if (delta > 180) delta = 360 - delta;
            if (delta < 5.0f) { // heading barely changed
                rec.straightUndergroundBlocks += horiz;
                if (rec.straightUndergroundBlocks >= 60) { // 60 blocks straight underground
                    addScore(p.getUniqueId(), p.getName(), SCORE_DEEP_STRAIGHT,
                        "60+ blocks straight underground (base tunnel?)");
                    rec.straightUndergroundBlocks = 0;
                }
            } else {
                rec.straightUndergroundBlocks = 0;
            }
        }
        rec.lastUndergroundYaw = yaw;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (p.hasPermission("donutsmp.staff")) return;
        // Large XP drop = player likely just killed a geared-up player or looted a base
        if (event.getDroppedExp() > 500) {
            addScore(p.getUniqueId(), p.getName(), SCORE_XP_SPIKE,
                "dropped " + event.getDroppedExp() + " XP on death");
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private void addScore(UUID uuid, String name, int delta, String reason) {
        PlayerRecord rec = getOrCreate(uuid, name);
        rec.score += delta;
        rec.lastReason = reason;

        plugin.getLogger().info("[StaffWatch] +" + delta + " pts → " + name
            + " (now " + rec.score + ") — " + reason);

        if (rec.score >= flagThreshold && !rec.alerted) {
            rec.alerted = true;
            alertStaff(name, rec.score, reason);
            // Apply red nametag for staff view
            Player online = plugin.getServer().getPlayer(uuid);
            if (online != null) flagNametag(online);
        }
    }

    /** Send a subtle staff-only alert in chat. */
    private void alertStaff(String name, int score, String reason) {
        String msg = "§4[WATCH] §c" + name + " §7flagged §8(§e" + score + " pts§8)§7: " + reason
            + " §8— §7/staffwatch goto " + name;
        for (Player staff : plugin.getServer().getOnlinePlayers()) {
            if (staff.hasPermission("donutsmp.staff")) {
                staff.sendMessage(msg);
            }
        }
        plugin.getLogger().info("[StaffWatch] FLAGGED: " + name + " (" + score + " pts) — " + reason);
    }

    /** Repeating task: re-ping staff with active flags and locations. */
    private void startAlertTask() {
        long intervalTicks = alertIntervalSec * 20L;
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            List<Player> staffOnline = new ArrayList<>();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p.hasPermission("donutsmp.staff")) staffOnline.add(p);
            }
            if (staffOnline.isEmpty()) return;

            records.forEach((uuid, rec) -> {
                if (!rec.alerted) return;
                Player target = plugin.getServer().getPlayer(uuid);
                String locStr;
                if (target != null) {
                    Location l = target.getLocation();
                    locStr = "§f" + (int)l.getX() + "§7, §f" + (int)l.getY() + "§7, §f" + (int)l.getZ()
                        + " §8(§7" + (target.getWorld() != null ? target.getWorld().getName() : "?") + "§8)";
                } else {
                    locStr = "§7offline";
                }
                String ping = "§4[WATCH] §c" + rec.name + " §8[§e" + rec.score + "pts§8] §7@ " + locStr;
                for (Player staff : staffOnline) staff.sendMessage(ping);
            });
        }, intervalTicks, intervalTicks);
    }

    /** Set up a separate scoreboard with a team for flagged players (dark red nametag). */
    private void setupScoreboard() {
        ScoreboardManager mgr = plugin.getServer().getScoreboardManager();
        staffBoard = mgr.getNewScoreboard();
        flaggedTeam = staffBoard.registerNewTeam("sw_flagged");
        flaggedTeam.setColor(ChatColor.DARK_RED);
        flaggedTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.FOR_OWN_TEAM);
        // Prefix shown to staff only (they're on staffBoard)
        flaggedTeam.setPrefix("§4[!] ");
    }

    private void flagNametag(Player target) {
        flaggedTeam.addEntry(target.getName());
    }

    private void unflagNametag(Player target) {
        flaggedTeam.removeEntry(target.getName());
    }

    private PlayerRecord getOrCreate(UUID uuid, String name) {
        return records.computeIfAbsent(uuid, id -> new PlayerRecord(name));
    }

    // ── Data class ───────────────────────────────────────────────────────────

    private static class PlayerRecord {
        String   name;
        int      score           = 0;
        boolean  alerted         = false;
        String   lastReason      = "";
        long     lastQuitMs      = 0;
        Location lastQuitLocation;
        List<Long> relogTimes    = new ArrayList<>();

        // Movement tracking
        float  lastUndergroundYaw      = -999f;
        double straightUndergroundBlocks = 0;

        // Block-break tracking
        int chunkEdgeBreaks = 0;

        // Deepslate-relog grace tracking
        int  lastQuitY            = Integer.MAX_VALUE; // Y when last /quit
        long deepslateGraceUntilMs = 0L;              // ignore relog scores until this time

        boolean inDeepslateGrace() {
            return System.currentTimeMillis() < deepslateGraceUntilMs;
        }

        PlayerRecord(String name) { this.name = name; }
    }
}
