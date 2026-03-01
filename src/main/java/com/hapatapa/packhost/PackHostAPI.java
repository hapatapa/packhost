package com.hapatapa.packhost;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Public API surface for the PackHost library.
 *
 * <p>
 * Other plugins call these static methods — no need to depend on any
 * PackHost internals beyond this class and {@link PackEntry}.
 *
 * <h2>Early vs non-early packs</h2>
 * <ul>
 * <li><b>earlyLoad = true</b> — The pack is required before a player may join.
 * PackHost blocks logins until it is hashed and served. It is automatically
 * included in the bundle sent to every player on join.</li>
 * <li><b>earlyLoad = false</b> — The pack is hashed and served but is
 * <em>not</em> sent automatically. Call {@link #sendPack} whenever you want
 * to push it to a player (e.g. on dimension change).</li>
 * </ul>
 */
public final class PackHostAPI {

    private PackHostAPI() {
    }

    /**
     * Register a resource pack whose contents live inside your plugin JAR under
     * {@code classpathDir}.
     *
     * @param caller       The calling plugin (used for labelling and classpath
     *                     access)
     * @param classpathDir Root of the resource pack inside the JAR (e.g.
     *                     {@code "/resource_pack"})
     * @param earlyLoad    {@code true} → required at login and blocks join until
     *                     ready;
     *                     {@code false} → served but sent only when you call
     *                     {@link #sendPack}
     * @param onReady      Called (on an async thread) when the pack is hashed and
     *                     being served.
     *                     May be {@code null}.
     */
    public static void registerPack(JavaPlugin caller, String classpathDir,
            boolean earlyLoad, Consumer<PackEntry> onReady) {
        PackHostPlugin.getInstance().registerPluginPack(caller, classpathDir, earlyLoad, onReady);
    }

    /**
     * Build an Adventure {@link ResourcePackRequest} containing all early-load
     * packs
     * (plugin packs registered with {@code earlyLoad=true} plus all custom packs),
     * sorted by their index in {@code pack_order.json}.
     *
     * @return the request, or {@code null} if no early packs are registered yet
     */
    public static ResourcePackRequest buildEarlyRequest() {
        return PackHostPlugin.getInstance().buildEarlyRequest();
    }

    /**
     * Send a single pack to a player right now, required by default.
     * Use this for non-early packs that should be pushed mid-game.
     *
     * @param player The target player
     * @param packId UUID of the pack (returned via the {@code onReady} callback)
     */
    public static void sendPack(Player player, UUID packId) {
        PackHostPlugin.getInstance().sendPackToPlayer(player, packId, true);
    }

    /**
     * Send a single pack to a player right now with an explicit required flag.
     *
     * @param player   The target player
     * @param packId   UUID of the pack
     * @param required {@code true} → kick player if they decline;
     *                 {@code false} → optional
     */
    public static void sendPack(Player player, UUID packId, boolean required) {
        PackHostPlugin.getInstance().sendPackToPlayer(player, packId, required);
    }
}
