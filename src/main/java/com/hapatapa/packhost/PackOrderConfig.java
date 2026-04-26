package com.hapatapa.packhost;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reads and writes plugins/PackHost/pack_order.json.
 *
 * Format:
 * 
 * <pre>
 * {
 *   "order": [
 *     { "index": 0, "id": "<uuid>", "label": "LODSystem" },
 *     { "index": 1, "id": "<uuid>", "label": "my_custom_pack.zip" }
 *   ]
 * }
 * </pre>
 *
 * index 0 = top of the Minecraft pack list (applied last, highest priority).
 * Admins edit this file and restart to reorder packs.
 */
public class PackOrderConfig {

    private final File file;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public PackOrderConfig(File file) {
        this.file = file;
    }

    /**
     * Applies saved indices from pack_order.json to the entries list, then saves
     * an updated file (appending any new packs not yet in the file at the end).
     * Must be called after all packs have been registered.
     */
    public void applyAndSave(List<PackEntry> entries) {
        Map<UUID, Integer> savedIndices = load();

        // Find the highest existing index so new packs are appended after it
        int maxIndex = savedIndices.values().stream().mapToInt(i -> i).max().orElse(-1);

        for (PackEntry entry : entries) {
            if (savedIndices.containsKey(entry.getId())) {
                entry.setIndex(savedIndices.get(entry.getId()));
            } else {
                // New pack not in file yet — append at the end
                entry.setIndex(++maxIndex);
            }
        }

        save(entries);
    }

    /**
     * Loads UUID → index mapping from the file. Returns empty map if file is
     * missing or corrupt.
     */
    private Map<UUID, Integer> load() {
        if (!file.exists())
            return new LinkedHashMap<>();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject())
                return new LinkedHashMap<>();
            JsonArray order = root.getAsJsonObject().getAsJsonArray("order");
            if (order == null)
                return new LinkedHashMap<>();

            Map<UUID, Integer> result = new LinkedHashMap<>();
            for (JsonElement el : order) {
                JsonObject obj = el.getAsJsonObject();
                UUID id = UUID.fromString(obj.get("id").getAsString());
                int index = obj.get("index").getAsInt();
                result.put(id, index);
            }
            return result;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** Saves the current ordered list to pack_order.json. */
    private void save(List<PackEntry> entries) {
        JsonObject root = new JsonObject();
        JsonArray order = new JsonArray();

        entries.stream()
                .sorted(Comparator.comparingInt(PackEntry::getIndex))
                .forEach(e -> {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("index", e.getIndex());
                    obj.addProperty("id", e.getId().toString());
                    obj.addProperty("label", e.getLabel());
                    order.add(obj);
                });

        root.add("order", order);

        try {
            file.getParentFile().mkdirs();
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
