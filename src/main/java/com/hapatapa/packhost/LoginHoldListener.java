package com.hapatapa.packhost;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * Holds connecting players on the login thread until all early-load packs are
 * hashed and served by the HTTP server.
 *
 * This prevents the race condition where a player joins and receives the pack
 * request before PackHost has finished processing it.
 *
 * Timeout: 30 seconds. Players who time out are kicked with a friendly message.
 */
public class LoginHoldListener implements Listener {

    private static final long TIMEOUT_MS = 30_000L;
    private static final long POLL_INTERVAL_MS = 100L;

    private final PackHostPlugin plugin;

    public LoginHoldListener(PackHostPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        // Fast path — already ready
        if (plugin.areEarlyPacksReady())
            return;

        plugin.getLogger().info("[PackHost] Holding login for " + event.getName()
                + " — waiting for early packs to be ready...");

        long start = System.currentTimeMillis();
        while (!plugin.areEarlyPacksReady()) {
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > TIMEOUT_MS) {
                event.disallow(
                        AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text("Server resource packs are still loading. Please reconnect in a moment.",
                                NamedTextColor.RED));
                plugin.getLogger().warning("[PackHost] Login hold timed out for " + event.getName());
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        plugin.getLogger().info("[PackHost] Early packs ready — releasing login for " + event.getName());
    }
}
