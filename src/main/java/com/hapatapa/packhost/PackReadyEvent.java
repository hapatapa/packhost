package com.hapatapa.packhost;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Bukkit event fired on the main thread once all early-load packs are hashed
 * and the HTTP server is ready to serve them.
 *
 * Other plugins that registered non-early packs and want to wait for PackHost
 * to be fully initialised can listen for this event instead of polling.
 */
public class PackReadyEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    public PackReadyEvent() {
        super(false); // not async — always fired on main thread
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
