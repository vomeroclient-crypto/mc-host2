package com.donutsmp.plugin;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads .litematic files from a website into plugins/DonutSMPWorld/schematics/ and registers them.
 *
 * A URL can be:
 *   - a direct link to a .litematic file
 *   - a link to a .zip containing .litematic files
 *   - a plain-text index: one URL per line (relative links work, lines starting with # are ignored)
 */
public class SchematicFetcher {

    private static final long MAX_BYTES = 50L * 1024 * 1024;

    private final DonutSMPPlugin plugin;
    private final LitematicLoader loader;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public SchematicFetcher(DonutSMPPlugin plugin, LitematicLoader loader) {
        this.plugin = plugin;
        this.loader = loader;
    }

    /** Downloads everything at the given URLs (async), registers new schematics, then calls msg on the main thread. */
    public void fetch(List<String> urls, Consumer<String> msg) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int saved = 0;
            List<String> errors = new ArrayList<>();
            for (String u : urls) {
                try { saved += fetchUrl(u.trim(), 0); }
                catch (Exception e) {
                    errors.add(u + " -> " + e.getMessage());
                    plugin.getLogger().warning("Fetch failed for " + u + ": " + e);
                }
            }
            final int total = saved;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                int added = loader.registerFromFolder();
                loader.applySavedRenames();
                msg.accept("Downloaded " + total + " file(s), registered " + added + " new schematic(s). Total: "
                        + LitematicLoader.SCHEMATICS.size() + (errors.isEmpty() ? "" : ". Errors: " + String.join(" | ", errors)));
            });
        });
    }

    private int fetchUrl(String url, int depth) throws Exception {
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https") && !scheme.equals("http")) throw new IOException("only http/https URLs allowed");
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();

        byte[] data = download(uri);

        if (path.endsWith(".litematic")) {
            return saveFile(lastSegment(uri.getPath()), data) ? 1 : 0;
        }
        if (path.endsWith(".zip")) {
            int n = 0;
            try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (e.isDirectory() || !e.getName().toLowerCase().endsWith(".litematic")) continue;
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int r;
                    while ((r = zin.read(buf)) > 0) {
                        bos.write(buf, 0, r);
                        if (bos.size() > MAX_BYTES) throw new IOException("zip entry too large");
                    }
                    if (saveFile(lastSegment(e.getName()), bos.toByteArray())) n++;
                }
            }
            return n;
        }
        // Otherwise: a text index of URLs (one level deep only)
        if (depth > 0) throw new IOException("unsupported link (expected .litematic or .zip): " + url);
        int n = 0;
        for (String line : new String(data, java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            n += fetchUrl(uri.resolve(line).toString(), depth + 1);
        }
        return n;
    }

    private byte[] download(URI uri) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        try (InputStream in = resp.body()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int r;
            while ((r = in.read(buf)) > 0) {
                bos.write(buf, 0, r);
                if (bos.size() > MAX_BYTES) throw new IOException("file larger than 50 MB");
            }
            return bos.toByteArray();
        }
    }

    private boolean saveFile(String rawName, byte[] data) throws IOException {
        // Litematics are gzip files; skip anything that isn't.
        if (data.length < 2 || (data[0] & 0xFF) != 0x1f || (data[1] & 0xFF) != 0x8b) {
            plugin.getLogger().warning("Skipping " + rawName + " (not a gzip/litematic file)");
            return false;
        }
        String name = applyRename(rawName).replaceAll("[^A-Za-z0-9._-]", "_");
        if (!name.toLowerCase().endsWith(".litematic")) return false;
        File dir = new File(plugin.getDataFolder(), "schematics");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(data);
        }
        plugin.getLogger().info("Downloaded schematic: " + name + " (" + data.length + " bytes)");
        return true;
    }

    /**
     * Rename step for downloaded files:
     *  1. strips browser-style duplicate suffixes: "Base (1).litematic" -> "Base.litematic"
     *  2. applies config rules in schematic-renames, each written as "from -> to"
     *     (case-insensitive, ".litematic" optional on both sides)
     */
    private String applyRename(String rawName) {
        String clean = rawName.replaceAll("\\s*\\(\\d+\\)(?=\\.litematic$)", "");
        for (String rule : plugin.getConfig().getStringList("schematic-renames")) {
            String[] parts = rule.split("->", 2);
            if (parts.length != 2) continue;
            String from = stripExt(parts[0].trim()), to = parts[1].trim();
            if (from.isEmpty() || to.isEmpty()) continue;
            if (from.equalsIgnoreCase(stripExt(rawName)) || from.equalsIgnoreCase(stripExt(clean))) {
                String result = to.toLowerCase().endsWith(".litematic") ? to : to + ".litematic";
                plugin.getLogger().info("Renamed download " + rawName + " -> " + result);
                return result;
            }
        }
        return clean;
    }

    private static String stripExt(String n) {
        return n.toLowerCase().endsWith(".litematic") ? n.substring(0, n.length() - ".litematic".length()) : n;
    }

    private static String lastSegment(String path) {
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
