package com.donutsmp.plugin;

import java.util.List;
import java.util.ArrayList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.ChatColor;
import org.bukkit.Material;

import java.io.File;

public class DonutSMPPlugin extends JavaPlugin {

    private BaseGenerator    baseGenerator;
    private GeodeGenerator   geodeGenerator;
    private StashGenerator   stashGenerator;
    private ActivityTracker  activityTracker;
    private LitematicLoader  litematicLoader;
    private SchematicFetcher schematicFetcher;
    private NoSpawnZones noSpawnZones;
    private SchematicPlacer  schematicPlacer;

    private FarmManager    farmManager;
    private StaffWatcher   staffWatcher;
    private HomeManager    homeManager;
    private SellManager    sellManager;
    private RTPManager     rtpManager;
    private JoinKitManager joinKitManager;
    private ElytraManager  elytraManager;
    private FreeShop       freeShop;

    private PlacementRegistry placementRegistry;

    public NoSpawnZones getNoSpawnZones() { return noSpawnZones; }
    public StaffWatcher getStaffWatcher() { return staffWatcher; }
    public PlacementRegistry getPlacementRegistry() { return placementRegistry; }
    public ActivityTracker getActivityTracker() { return activityTracker; }
    public FarmManager getFarmManager() { return farmManager; }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        File schematicsDir = new File(getDataFolder(), "schematics");
        if (!schematicsDir.exists()) schematicsDir.mkdirs();
        // Copy bundled .litematic files into plugins/DonutSMPWorld/schematics/ (never overwrites existing files)
        for (LitematicLoader.SchematicMeta m : LitematicLoader.SCHEMATICS.values()) {
            File target = new File(schematicsDir, m.filename);
            if (!target.exists()) {
                try { saveResource("schematics/" + m.filename, false); }
                catch (IllegalArgumentException e) { getLogger().warning("Bundled schematic missing from jar: " + m.filename); }
            }
        }

        this.activityTracker = new ActivityTracker(this);
        this.baseGenerator   = new BaseGenerator(this, activityTracker);
        this.geodeGenerator  = new GeodeGenerator(this, activityTracker);
        this.stashGenerator  = new StashGenerator(this);
        this.noSpawnZones    = new NoSpawnZones(this);
        getServer().getPluginManager().registerEvents(noSpawnZones, this);
        this.placementRegistry = new PlacementRegistry(this);
        this.litematicLoader = new LitematicLoader(this);
        this.schematicPlacer = new SchematicPlacer(this, litematicLoader, activityTracker);
        this.schematicFetcher = new SchematicFetcher(this, litematicLoader);
        litematicLoader.registerFromFolder(); // picks up any extra .litematic files you drop in the folder
        litematicLoader.applySavedRenames();
        this.farmManager = new FarmManager(this, litematicLoader);
        placementRegistry.importLegacy(noSpawnZones, farmManager); // first run only: learn about bases that already exist
        if (getConfig().getBoolean("fetch-schematics-on-startup", false)) {
            List<String> srcs = getConfig().getStringList("schematic-sources");
            if (!srcs.isEmpty()) schematicFetcher.fetch(srcs, m -> getLogger().info("[fetch] " + m));
        }

        this.staffWatcher   = new StaffWatcher(this);
        getServer().getPluginManager().registerEvents(staffWatcher, this);
        this.homeManager    = new HomeManager(this);
        this.sellManager    = new SellManager(this);
        this.rtpManager     = new RTPManager(this);
        this.elytraManager  = new ElytraManager(this);
        this.joinKitManager = new JoinKitManager(this, elytraManager);
        this.freeShop       = new FreeShop(this);

        getServer().getPluginManager().registerEvents(activityTracker, this);
        getServer().getPluginManager().registerEvents(elytraManager,   this);
        getServer().getPluginManager().registerEvents(joinKitManager,  this);
        getServer().getPluginManager().registerEvents(freeShop,        this);
        getServer().getPluginManager().registerEvents(new KeepInventoryListener(this),          this);
        getServer().getPluginManager().registerEvents(new DrillPickaxeListener(),              this);
        getServer().getPluginManager().registerEvents(new RespawnKitRestorer(this, elytraManager), this);
        getServer().getPluginManager().registerEvents(new FireworkSafetyListener(),            this);
        getServer().getPluginManager().registerEvents(new RandomRespawnListener(this, rtpManager), this);

        checkSeed();
        getLogger().info("DonutSMP Plugin v7 enabled!");
    }

    /** Warn if the overworld isn't the seed this plugin was set up for. */
    private void checkSeed() {
        long expected = getConfig().getLong("world-seed", -3868604108235547403L);
        long actual   = getServer().getWorlds().get(0).getSeed();
        if (expected == actual) {
            getLogger().info("World seed matches (" + actual + ").");
        } else {
            getLogger().warning("World seed is " + actual + " but config expects " + expected
                + ". Set level-seed in server.properties and delete the world folders to regenerate.");
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("DonutSMP Plugin disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {

            // ── HOME ─────────────────────────────────────────────────────────
            case "sethome":
                if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
                homeManager.setHome((Player) sender);
                return true;

            case "home":
                if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
                homeManager.goHome((Player) sender);
                return true;

            // ── SELL ─────────────────────────────────────────────────────────
            case "sell":
                if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
                Player sellPlayer = (Player) sender;
                if (args.length > 0 && args[0].equalsIgnoreCase("all")) {
                    sellManager.sellAll(sellPlayer);
                } else {
                    sellManager.sellHand(sellPlayer);
                }
                return true;

            case "bal":
            case "balance":
                if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
                sellManager.showBalance((Player) sender);
                return true;

            case "sellprices":
                sender.sendMessage(ChatColor.GOLD + "=== DonutSMP Sell Prices (top 50) ===");
                SellManager.getPrices().entrySet().stream()
                    .sorted(Map.Entry.<Material, Double>comparingByValue().reversed())
                    .limit(50)
                    .forEach(e -> sender.sendMessage(
                        ChatColor.YELLOW + e.getKey().name().toLowerCase().replace("_", " ")
                        + ChatColor.WHITE + " → $" + String.format("%.2f", e.getValue())));
                sender.sendMessage(ChatColor.GRAY + "(Enchanted gear sells for more — bonus scales with enchant level)");
                return true;

            // ── KIT ──────────────────────────────────────────────────────────
            case "givekit":
                if (!sender.hasPermission("donutsmp.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                Player kitTarget;
                if (args.length > 0) {
                    kitTarget = getServer().getPlayerExact(args[0]);
                    if (kitTarget == null) { sender.sendMessage(ChatColor.RED + "Player not found."); return true; }
                } else if (sender instanceof Player) {
                    kitTarget = (Player) sender;
                } else { sender.sendMessage("Provide a player name."); return true; }
                joinKitManager.forceGiveKit(kitTarget);
                if (!kitTarget.equals(sender)) sender.sendMessage(ChatColor.GREEN + "Kit given to " + kitTarget.getName() + ".");
                return true;

            // ── RTP ──────────────────────────────────────────────────────────
            case "rtp":
                if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
                Player rtpPlayer = (Player) sender;
                if (args.length == 0) {
                    rtpManager.rtp(rtpPlayer);
                } else {
                    rtpManager.getRegion(args[0]).ifPresentOrElse(
                        region -> rtpManager.rtp(rtpPlayer, region),
                        () -> {
                            rtpPlayer.sendMessage(ChatColor.RED + "Unknown region: " + args[0]);
                            rtpPlayer.sendMessage(ChatColor.YELLOW + "Regions: overworld, wild, easy, mid, hard, nether, end");
                        }
                    );
                }
                return true;

            case "rtpaddexclusion":
                if (!sender.hasPermission("donutsmp.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (args.length < 3) { sender.sendMessage(ChatColor.YELLOW + "Usage: /rtpaddexclusion <x> <z> <radius>"); return true; }
                try {
                    rtpManager.addExclusion(Integer.parseInt(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]));
                    sender.sendMessage(ChatColor.GREEN + "RTP exclusion zone added.");
                } catch (NumberFormatException nfe) { sender.sendMessage(ChatColor.RED + "X, Z, radius must be integers."); }
                return true;

            // ── Original world-gen commands ───────────────────────────────────
            case "generatebases":
                if (!sender.hasPermission("donutsmp.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.YELLOW + "Generating procedural underground bases...");
                // Must run on the main thread: Paper forbids chunk loads from async tasks.
                getServer().getScheduler().runTask(this, () -> {
                    int count = baseGenerator.generateAllBases();
                    sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN + "Generated " + count + " bases!");
                });
                return true;

            case "placeschematics":
                if (!sender.hasPermission("donutsmp.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.YELLOW + "Placing schematics...");
                // One schematic every 2 seconds on the main thread (no async chunk loads, no single huge lag spike).
                getServer().getScheduler().runTask(this, () ->
                    schematicPlacer.placeAllSchematicsSliced(count ->
                        sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN + "Placed " + count + "/" + LitematicLoader.SCHEMATICS.size() + " schematics!")));
                return true;

            case "renameschematic":
                if (!sender.hasPermission("donutsmp.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /renameschematic <current name> <new name>");
                    sender.sendMessage(ChatColor.YELLOW + "Current: " + String.join(", ", LitematicLoader.SCHEMATICS.keySet()));
                    return true;
                }
                {
                    String oldK = args[0].toLowerCase();
                    String err = litematicLoader.renameKey(oldK, args[1]);
                    if (err != null) { sender.sendMessage(ChatColor.RED + err); return true; }
                    String newK = args[1].toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
                    // Persist: update an existing chain (a -> oldK) or add oldK -> newK
                    List<String> rules = new ArrayList<>(getConfig().getStringList("key-renames"));
                    boolean chained = false;
                    for (int i = 0; i < rules.size(); i++) {
                        String[] pr = rules.get(i).split("->", 2);
                        if (pr.length == 2 && pr[1].trim().equals(oldK)) { rules.set(i, pr[0].trim() + " -> " + newK); chained = true; break; }
                    }
                    if (!chained) rules.add(oldK + " -> " + newK);
                    getConfig().set("key-renames", rules);
                    saveConfig();
                    sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN + "Renamed '" + oldK + "' to '" + newK + "'. Use /placeschematic " + newK);
                }
                return true;

            case "fetchschematics":
                if (!sender.hasPermission("donutsmp.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                {
                    List<String> urls = args.length > 0 ? new ArrayList<>(java.util.Arrays.asList(args)) : getConfig().getStringList("schematic-sources");
                    if (urls.isEmpty()) { sender.sendMessage(ChatColor.YELLOW + "Usage: /fetchschematics <url> (a .litematic, a .zip, or a text list of URLs) — or set schematic-sources in config.yml"); return true; }
                    sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.YELLOW + "Downloading schematics...");
                    schematicFetcher.fetch(urls, m -> sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN + m));
                }
                return true;

            case "placeschematicsaround":
                if (!sender.hasPermission("donutsmp.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (!(sender instanceof org.bukkit.entity.Player)) { sender.sendMessage(ChatColor.RED + "Players only."); return true; }
                {
                    org.bukkit.entity.Player pl = (org.bukkit.entity.Player) sender;
                    int radius = 60;
                    List<String> keys = new ArrayList<>(LitematicLoader.SCHEMATICS.keySet());
                    for (String a : args) {
                        try { radius = Math.max(20, Integer.parseInt(a)); }
                        catch (NumberFormatException nfe) {
                            String kk = a.toLowerCase();
                            if (LitematicLoader.SCHEMATICS.containsKey(kk)) { keys = new ArrayList<>(); keys.add(kk); }
                            else if (!kk.equals("all")) { sender.sendMessage(ChatColor.RED + "Unknown schematic '" + a + "'. Available: " + String.join(", ", LitematicLoader.SCHEMATICS.keySet())); return true; }
                        }
                    }
                    final int fr = radius; final List<String> fk = keys; final org.bukkit.Location at = pl.getLocation();
                    sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.YELLOW + "Placing " + fk.size() + " schematic(s) around you (one every 2s)...");
                    getServer().getScheduler().runTask(this, () ->
                        schematicPlacer.placeAround(at, fr, fk, count ->
                            sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN + "Placed " + count + "/" + fk.size() + " around you!")));
                }
                return true;

            case "placeschematic":
                if (!sender.hasPermission("donutsmp.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (args.length < 1) { sender.sendMessage(ChatColor.YELLOW + "Available: " + String.join(", ", LitematicLoader.SCHEMATICS.keySet()) + ""); return true; }
                String key = args[0].toLowerCase();
                getServer().getScheduler().runTask(this, () -> {
                    boolean ok = schematicPlacer.placeSchematic(key);
                    sender.sendMessage(ok ? ChatColor.GREEN + "Placed " + key + "!" : ChatColor.RED + "Failed — check schematics folder.");
                });
                return true;

            case "generategeodes":
                if (!sender.hasPermission("donutsmp.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.YELLOW + "Generating geodes...");
                getServer().getScheduler().runTask(this, () -> {
                    geodeGenerator.generateGeodes();
                    // geodes are built a tick later, so report after a short delay
                    getServer().getScheduler().runTaskLater(this, () ->
                        sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN + "Generated " + geodeGenerator.getGeneratedCount() + " geodes!"), 20L);
                });
                return true;

            // ── REMOVE CROWDED BASES ──────────────────────────────────────────
            case "removebase": {
                // /removebase [preview] [minGap]
                if (!sender.hasPermission("donutsmp.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                boolean previewOnly = false;
                int minGap = placementRegistry.configuredGap();
                if (minGap <= 0) minGap = getConfig().getInt("base-spacing.min-gap", 150);
                for (String a : args) {
                    if (a.equalsIgnoreCase("preview") || a.equalsIgnoreCase("list")) { previewOnly = true; continue; }
                    try { minGap = Math.max(1, Integer.parseInt(a)); }
                    catch (NumberFormatException nfe) {
                        sender.sendMessage(ChatColor.YELLOW + "Usage: /removebase [preview] [minGap]");
                        sender.sendMessage(ChatColor.GRAY + "Removes bases/stashes/schematics closer than minGap blocks (edge to edge) to an older one. Default "
                                + minGap + ". Farms and hand-placed bases are never removed.");
                        return true;
                    }
                }
                List<PlacementRegistry.Removal> doomed = placementRegistry.findCrowded(minGap);
                if (doomed.isEmpty()) {
                    sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN + "Nothing is closer than " + minGap
                            + " blocks to anything else (" + placementRegistry.size() + " placements tracked).");
                    return true;
                }
                sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.YELLOW + (previewOnly ? "Would remove " : "Removing ")
                        + doomed.size() + " base(s) closer than " + minGap + " blocks to an older one:");
                int shown = 0;
                for (PlacementRegistry.Removal r : doomed) {
                    if (shown++ >= 15) { sender.sendMessage(ChatColor.GRAY + "  ...and " + (doomed.size() - 15) + " more"); break; }
                    sender.sendMessage(ChatColor.RED + "  - " + r.entry.describe() + ChatColor.GRAY + "  (" + r.gap + " blocks from "
                            + r.because.kind + " '" + r.because.key + "')");
                }
                if (previewOnly) {
                    sender.sendMessage(ChatColor.GRAY + "Run /removebase" + (minGap != placementRegistry.configuredGap() ? " " + minGap : "") + " to actually remove them.");
                    return true;
                }
                List<PlacementRegistry.Entry> toRemove = new ArrayList<>();
                for (PlacementRegistry.Removal r : doomed) toRemove.add(r.entry);
                final int removedCount = toRemove.size();
                sender.sendMessage(ChatColor.GRAY + "Clearing blocks a bit at a time so the server doesn't lag...");
                placementRegistry.removeAll(toRemove, () ->
                    sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN + "Removed " + removedCount + " crowded base(s)."));
                return true;
            }

            case "generatestash":
                if (!sender.hasPermission("donutsmp.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (!(sender instanceof Player)) { sender.sendMessage("Must be a player."); return true; }
                Player stashPlayer = (Player) sender;
                stashGenerator.generateStashAt(stashPlayer.getLocation(), args.length > 0 ? args[0].toLowerCase() : "nomad");
                sender.sendMessage(ChatColor.GOLD + "[DonutSMP] " + ChatColor.GREEN + "Stash placed!");
                return true;

            // ── FARMS ─────────────────────────────────────────────────────────

            case "listfarms": {
                // /listfarms [type]  — lists all registered farms, optionally filtered by type
                String filter = args.length > 0 ? args[0] : null;
                farmManager.listFarms(sender, filter);
                return true;
            }

            case "unusedfarms": {
                // /unusedfarms  — lists farms that have never been placed in the world
                farmManager.listUnusedFarms(sender);
                return true;
            }

            case "addfarm": {
                // /addfarm <key> <filename.litematic> [type] [description...]
                // Registers a .litematic already in the schematics folder as a named farm.
                if (!sender.hasPermission("donutsmp.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                farmManager.addFarm(sender, args);
                return true;
            }

            case "spawnfarm": {
                // /spawnfarm <key>  — paste a farm schematic at your feet
                if (!sender.hasPermission("donutsmp.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
                if (args.length < 1) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /spawnfarm <key>");
                    sender.sendMessage(ChatColor.YELLOW + "Use /listfarms to see available keys.");
                    return true;
                }
                farmManager.spawnFarm((Player) sender, args[0]);
                return true;
            }

            case "farmtoggle":
            case "ft": {
                // /farmtoggle <key> [on|off]  — start or stop a placed farm
                if (!sender.hasPermission("donutsmp.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (args.length < 1) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /farmtoggle <key> [on|off]");
                    sender.sendMessage(ChatColor.YELLOW + "Use /listfarms to see placed farms.");
                    return true;
                }
                Boolean forceState = null;
                if (args.length >= 2) {
                    if (args[1].equalsIgnoreCase("on"))  forceState = true;
                    else if (args[1].equalsIgnoreCase("off")) forceState = false;
                    else { sender.sendMessage(ChatColor.RED + "Second argument must be 'on' or 'off' (or omit to toggle)."); return true; }
                }
                farmManager.toggleFarm(sender, args[0], forceState);
                return true;
            }

            // ── FREE SHOP ─────────────────────────────────────────────────────
            case "freeshop":
                if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
                freeShop.open((Player) sender);
                return true;

            // ── STAFF WATCH ───────────────────────────────────────────────────
            case "staffwatch":
            case "sw": {
                if (!sender.hasPermission("donutsmp.staff")) {
                    sender.sendMessage(ChatColor.RED + "No permission.");
                    return true;
                }
                String sub = args.length > 0 ? args[0].toLowerCase() : "list";
                switch (sub) {
                    case "list":
                        sender.sendMessage(ChatColor.GOLD + "=== StaffWatch — Flagged Players ===");
                        staffWatcher.listSummary().forEach(sender::sendMessage);
                        return true;
                    case "clear":
                        if (args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "Usage: /staffwatch clear <name>"); return true; }
                        Player swClear = getServer().getPlayerExact(args[1]);
                        if (swClear != null) {
                            staffWatcher.clearRecord(swClear.getUniqueId());
                            sender.sendMessage(ChatColor.GREEN + "Cleared record for " + swClear.getName() + ".");
                        } else {
                            sender.sendMessage(ChatColor.RED + "Player not online (can only clear online players).");
                        }
                        return true;
                    case "goto":
                        if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
                        if (args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "Usage: /staffwatch goto <name>"); return true; }
                        staffWatcher.gotoPlayer((Player) sender, args[1]);
                        return true;
                    case "flag":
                        if (args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "Usage: /staffwatch flag <name>"); return true; }
                        Player swTarget = getServer().getPlayerExact(args[1]);
                        if (swTarget == null) { sender.sendMessage(ChatColor.RED + "Player not online."); return true; }
                        staffWatcher.manualFlag(swTarget, sender);
                        sender.sendMessage(ChatColor.GREEN + "Manually flagged " + swTarget.getName() + ".");
                        return true;
                    default:
                        sender.sendMessage(ChatColor.YELLOW + "Usage: /staffwatch [list|clear <name>|goto <name>|flag <name>]");
                        return true;
                }
            }

            case "donutinfo":
                sender.sendMessage(ChatColor.GOLD + "=== DonutSMP Plugin v6 ===");
                sender.sendMessage(ChatColor.YELLOW + "Bases: " + ChatColor.WHITE + baseGenerator.getGeneratedCount());
                sender.sendMessage(ChatColor.YELLOW + "Schematics: " + ChatColor.WHITE + schematicPlacer.getPlacedCount() + "/" + LitematicLoader.SCHEMATICS.size());
                sender.sendMessage(ChatColor.YELLOW + "Geodes: " + ChatColor.WHITE + geodeGenerator.getGeneratedCount());
                sender.sendMessage(ChatColor.YELLOW + "Activity zones: " + ChatColor.WHITE + activityTracker.getZoneCount());
                sender.sendMessage(ChatColor.YELLOW + "Tracked placements: " + ChatColor.WHITE + placementRegistry.size()
                        + ChatColor.GRAY + " (min spacing " + placementRegistry.configuredGap() + ")");
                for (Map.Entry<String, LitematicLoader.SchematicMeta> e : LitematicLoader.SCHEMATICS.entrySet()) {
                    LitematicLoader.SchematicMeta m = e.getValue();
                    String diff = m.difficulty == 3 ? ChatColor.RED + "HARD" : m.difficulty == 2 ? ChatColor.YELLOW + "MED " : ChatColor.GREEN + "EASY";
                    sender.sendMessage(" " + diff + ChatColor.WHITE + " " + e.getKey() + " — " + m.displayName);
                }
                return true;
        }
        return false;
    }
}
