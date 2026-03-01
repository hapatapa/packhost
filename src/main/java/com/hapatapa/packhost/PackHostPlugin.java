package com.hapatapa.packhost;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class PackHostPlugin extends JavaPlugin implements Listener {

    private static PackHostPlugin instance;

    private final PackHttpServer httpServer = new PackHttpServer();
    /** Thread-safe list of all registered packs (plugins + custom). */
    private final List<PackEntry> registeredPacks = new CopyOnWriteArrayList<>();

    private final AtomicBoolean earlyPacksReady = new AtomicBoolean(false);
    /**
     * Counter of early packs that still need to be hashed.
     * Starts at 0; incremented before each async hash job, decremented when done.
     * When it returns to 0 (and all jobs have started),
     * {@link #onAllEarlyPacksHashed()} fires.
     */
    private final AtomicInteger pendingEarlyPacks = new AtomicInteger(0);
    /**
     * Set to true once all plugin-side registerPack calls have been submitted
     * (i.e. we're no longer expecting new early packs from onEnable calls).
     * Without this, if the first plugin's hash finishes before other plugins have
     * incremented the counter, we'd incorrectly fire PackReadyEvent too early.
     */
    private volatile boolean registrationPhaseComplete = false;

    private String publicAddress;
    private PackOrderConfig orderConfig;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        saveResource("config.yml", false); // never overwrite existing

        String host = getConfig().getString("host", "0.0.0.0");
        int port = getConfig().getInt("port", 8080);
        publicAddress = getConfig().getString("public-address", "").trim();

        // Start HTTP server
        try {
            httpServer.start(host, port);
            if (publicAddress.isEmpty()) {
                String ip = getServer().getIp();
                if (ip == null || ip.isEmpty() || ip.equals("0.0.0.0")) {
                    // Try to get actual local IP by connecting to a dummy socket
                    try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
                        socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
                        ip = socket.getLocalAddress().getHostAddress();
                    } catch (Exception ignored) {
                        ip = "localhost";
                    }
                }
                publicAddress = "http://" + ip + ":" + port;
            }
            getLogger().info("HTTP server started on " + host + ":" + port
                    + "  |  public address: " + publicAddress);
            if (publicAddress.contains("localhost")) {
                getLogger().warning(
                        "Public address is set to 'localhost'. External players will NOT be able to download packs!");
            }
        } catch (Exception e) {
            getLogger().severe("Failed to start HTTP server: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        orderConfig = new PackOrderConfig(new File(getDataFolder(), "pack_order.json"));

        // Register listeners
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new LoginHoldListener(this), this);

        // Scan custom_packs/ — all custom packs are early + required
        File customPacksDir = new File(getDataFolder(), "custom_packs");
        customPacksDir.mkdirs();
        File[] zips = customPacksDir.listFiles((d, n) -> n.endsWith(".zip"));

        if (zips != null && zips.length > 0) {
            pendingEarlyPacks.addAndGet(zips.length);
            for (File zip : zips) {
                getServer().getScheduler().runTaskAsynchronously(this, () -> registerCustomPack(zip));
            }
        }

        // All plugin registrations happen during onEnable of dependent plugins
        // (which run after PackHost's onEnable finishes). We schedule a 1-tick
        // delayed task so that all dependent plugin onEnables have had a chance
        // to call registerPack() before we consider the registration phase over.
        getServer().getScheduler().runTask(this, () -> {
            registrationPhaseComplete = true;
            // If nothing is pending (no custom packs and no plugin packs registered yet),
            // fire now
            if (pendingEarlyPacks.get() == 0) {
                onAllEarlyPacksHashed();
            }
        });
    }

    @Override
    public void onDisable() {
        httpServer.stop();
    }

    public static PackHostPlugin getInstance() {
        return instance;
    }

    // -------------------------------------------------------------------------
    // Pack registration (called by other plugins via PackHostAPI)
    // -------------------------------------------------------------------------

    /**
     * Called by other plugins (via PackHostAPI) to register a pack from their JAR.
     */
    public void registerPluginPack(JavaPlugin caller, String classpathDir,
            boolean earlyLoad, Consumer<PackEntry> onReady) {
        if (earlyLoad) {
            pendingEarlyPacks.incrementAndGet();
        }

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                // Deterministic UUID from plugin name + classpath path
                UUID id = UUID.nameUUIDFromBytes((caller.getName() + ":" + classpathDir).getBytes());
                String label = caller.getName();

                getLogger().info("Registering plugin pack [" + label + "] from " + classpathDir);

                // Extract classpath dir to a temp folder inside our data dir
                File tempDir = new File(getDataFolder(), ".tmp_" + id);
                tempDir.mkdirs();
                extractClasspathDir(caller, classpathDir, tempDir);

                // Zip the extracted dir to a byte array
                byte[] zipBytes = zipToBytes(tempDir.toPath());
                deleteDirectory(tempDir);

                // Compute SHA-1
                String sha1 = sha1Hex(zipBytes);

                // Register with HTTP server
                httpServer.registerPack(id, zipBytes);
                String url = publicAddress + "/packs/" + id + ".zip";

                PackEntry entry = new PackEntry(id, URI.create(url), sha1, label, earlyLoad);
                registeredPacks.add(entry);

                getLogger().info("Registered pack: [" + label + "] url=" + url + " sha1=" + sha1);

                if (onReady != null) {
                    onReady.accept(entry);
                }
            } catch (Exception e) {
                getLogger().warning("Failed to register pack from plugin '" + caller.getName()
                        + "': " + e.getMessage());
                e.printStackTrace();
            } finally {
                if (earlyLoad) {
                    int remaining = pendingEarlyPacks.decrementAndGet();
                    if (remaining == 0 && registrationPhaseComplete) {
                        onAllEarlyPacksHashed();
                    }
                }
            }
        });
    }

    /**
     * Registers a ZIP file dropped into custom_packs/ — always early + required.
     */
    private void registerCustomPack(File zip) {
        try {
            UUID id = UUID.nameUUIDFromBytes(zip.getName().getBytes());
            String label = zip.getName(); // e.g. "my_custom_pack.zip"

            byte[] zipBytes = Files.readAllBytes(zip.toPath());
            String sha1 = sha1Hex(zipBytes);

            httpServer.registerPack(id, zipBytes);
            String url = publicAddress + "/packs/" + id + ".zip";

            PackEntry entry = new PackEntry(id, URI.create(url), sha1, label, true);
            registeredPacks.add(entry);

            getLogger().info("Registered custom pack: [" + label + "] url=" + url + " sha1=" + sha1);
        } catch (Exception e) {
            getLogger().warning("Failed to register custom pack '" + zip.getName()
                    + "': " + e.getMessage());
            e.printStackTrace();
        } finally {
            int remaining = pendingEarlyPacks.decrementAndGet();
            if (remaining == 0 && registrationPhaseComplete) {
                onAllEarlyPacksHashed();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Ready event
    // -------------------------------------------------------------------------

    private void onAllEarlyPacksHashed() {
        // Apply and persist pack_order.json
        orderConfig.applyAndSave(registeredPacks);

        earlyPacksReady.set(true);

        getLogger().info("All early packs are ready and being served.");

        // PackReadyEvent must fire on the main thread
        getServer().getScheduler().runTask(this, () -> getServer().getPluginManager().callEvent(new PackReadyEvent()));
    }

    public boolean areEarlyPacksReady() {
        return earlyPacksReady.get();
    }

    // -------------------------------------------------------------------------
    // Pack sending
    // -------------------------------------------------------------------------

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Send early packs to joining players with a 20-tick delay to ensure client is
        // ready
        Player player = event.getPlayer();
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline())
                return;
            ResourcePackRequest request = buildEarlyRequest();
            if (request != null) {
                getLogger().info("Sending early pack bundle to " + player.getName() + " (" + request.packs().size()
                        + " packs)");
                player.sendResourcePacks(request);
            } else {
                getLogger().info("No early packs to send to " + player.getName());
            }
        }, 20L); // 1 second delay
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        getLogger().info("[PackStatus] " + event.getPlayer().getName() + ": " + event.getStatus());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("status")) {
                sender.sendMessage("§e--- PackHost Status ---");
                sender.sendMessage("§7Public Address: §f" + publicAddress);
                sender.sendMessage("§7Early Packs Ready: §f" + earlyPacksReady.get());
                sender.sendMessage("§7Registered Packs: §f" + registeredPacks.size());
                for (PackEntry entry : registeredPacks) {
                    sender.sendMessage("§8- §f" + entry.getLabel() + " §7(Early: " + entry.isEarlyLoad() + ", Id: "
                            + entry.getId() + ")");
                }
                return true;
            } else if (args[0].equalsIgnoreCase("send") && sender instanceof Player player) {
                ResourcePackRequest request = buildEarlyRequest();
                if (request != null) {
                    player.sendMessage("§aAttempting to send resource pack bundle...");
                    player.sendResourcePacks(request);
                } else {
                    player.sendMessage("§cNo early packs found to send.");
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Builds an Adventure ResourcePackRequest containing all early-load packs,
     * sorted by their configured index (0 = top of pack list).
     */
    public ResourcePackRequest buildEarlyRequest() {
        List<ResourcePackInfo> infos = new ArrayList<>();

        registeredPacks.stream()
                .filter(PackEntry::isEarlyLoad)
                .sorted(Comparator.comparingInt(PackEntry::getIndex))
                .forEach(e -> {
                    getLogger().info("Adding pack to bundle: " + e.getLabel() + " | URL: " + e.getUrl());
                    infos.add(
                            ResourcePackInfo.resourcePackInfo()
                                    .id(e.getId())
                                    .uri(e.getUrl())
                                    .hash(e.getSha1())
                                    .build());
                });

        if (infos.isEmpty())
            return null;

        return ResourcePackRequest.resourcePackRequest()
                .packs(infos)
                .required(true)
                .replace(false)
                .prompt(net.kyori.adventure.text.Component.text("This server uses custom resource packs."))
                .build();
    }

    /**
     * Sends a single registered pack to a player immediately (for non-early dynamic
     * packs).
     */
    public void sendPackToPlayer(Player player, UUID packId, boolean required) {
        registeredPacks.stream()
                .filter(e -> e.getId().equals(packId))
                .findFirst()
                .ifPresent(entry -> {
                    ResourcePackInfo info = ResourcePackInfo.resourcePackInfo()
                            .id(entry.getId())
                            .uri(entry.getUrl())
                            .hash(entry.getSha1())
                            .build();

                    ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                            .packs(info)
                            .required(required)
                            .replace(false)
                            .build();

                    player.sendResourcePacks(request);
                });
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /**
     * Extracts a classpath directory (works both from a JAR and from plain
     * filesystem).
     */
    private void extractClasspathDir(JavaPlugin caller, String classpathDir, File dest) throws Exception {
        // Normalize path for ZIP/JAR filesystems (usually no leading slash)
        String internalPath = classpathDir.startsWith("/") ? classpathDir.substring(1) : classpathDir;

        java.net.URL resource = caller.getClass()
                .getResource(classpathDir.startsWith("/") ? classpathDir : "/" + classpathDir);
        if (resource == null) {
            throw new IllegalArgumentException("Classpath resource not found: " + classpathDir
                    + " in plugin " + caller.getName());
        }
        URI uri = resource.toURI();

        if ("jar".equals(uri.getScheme())) {
            FileSystem fs;
            boolean closeFsAfter = false;
            try {
                fs = FileSystems.getFileSystem(uri);
            } catch (FileSystemNotFoundException e) {
                fs = FileSystems.newFileSystem(uri, Collections.emptyMap());
                closeFsAfter = true;
            }
            try {
                Path jarPath = fs.getPath(internalPath);
                Files.walk(jarPath).forEach(p -> {
                    try {
                        Path target = dest.toPath().resolve(jarPath.relativize(p).toString());
                        if (Files.isDirectory(p)) {
                            Files.createDirectories(target);
                        } else {
                            Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                });
            } finally {
                if (closeFsAfter)
                    fs.close();
            }
        } else {
            // Plain filesystem (e.g. during development / running from IDE)
            Path src = Paths.get(uri);
            Files.walk(src).forEach(p -> {
                try {
                    Path target = dest.toPath().resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            });
        }
    }

    /** Zips an entire directory tree into a byte array in memory. */
    private byte[] zipToBytes(Path sourceDir) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            Files.walk(sourceDir)
                    .filter(p -> !Files.isDirectory(p))
                    .forEach(p -> {
                        // Normalize path separators for cross-platform ZIPs
                        String entryName = sourceDir.relativize(p).toString().replace('\\', '/');
                        try {
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(p, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
        }
        return baos.toByteArray();
    }

    /** Returns the lowercase hex SHA-1 of the given bytes. */
    private String sha1Hex(byte[] data) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-1").digest(data);
        StringBuilder sb = new StringBuilder(40);
        for (byte b : hash)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Recursively deletes a directory. */
    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory())
                    deleteDirectory(f);
                else
                    f.delete();
            }
        }
        dir.delete();
    }
}
