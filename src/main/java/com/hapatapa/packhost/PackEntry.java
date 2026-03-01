package com.hapatapa.packhost;

import java.net.URI;
import java.util.UUID;

/**
 * Represents a single registered resource pack — its identity, URL, hash, and
 * metadata.
 */
public class PackEntry {

    private final UUID id;
    private final URI url;
    private final String sha1;
    private final String label;
    private final boolean earlyLoad;
    /**
     * Sort order for the Minecraft pack list. 0 = highest (topmost). Set by
     * PackOrderConfig.
     */
    private int index = Integer.MAX_VALUE;

    public PackEntry(UUID id, URI url, String sha1, String label, boolean earlyLoad) {
        this.id = id;
        this.url = url;
        this.sha1 = sha1;
        this.label = label;
        this.earlyLoad = earlyLoad;
    }

    public UUID getId() {
        return id;
    }

    public URI getUrl() {
        return url;
    }

    public String getSha1() {
        return sha1;
    }

    public String getLabel() {
        return label;
    }

    public boolean isEarlyLoad() {
        return earlyLoad;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int i) {
        this.index = i;
    }
}
