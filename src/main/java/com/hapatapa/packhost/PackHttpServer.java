package com.hapatapa.packhost;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Minimal embedded HTTP server that serves resource pack ZIPs.
 * Each pack is accessible at: GET /packs/{uuid}.zip
 */
public class PackHttpServer {

    private HttpServer server;
    private final Map<UUID, byte[]> packData = new ConcurrentHashMap<>();

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.createContext("/packs/", exchange -> {
            String remote = exchange.getRemoteAddress().toString();
            String path = exchange.getRequestURI().getPath();
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                // Path: /packs/<uuid>.zip
                String name = path.substring("/packs/".length());
                if (!name.endsWith(".zip")) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }

                UUID id;
                try {
                    id = UUID.fromString(name.substring(0, name.length() - 4));
                } catch (IllegalArgumentException e) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }

                byte[] data = packData.get(id);
                if (data == null) {
                    System.out.println("[PackHost-HTTP] 404 Not Found: " + path + " from " + remote);
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }

                System.out.println("[PackHost-HTTP] Serving " + path + " (" + data.length + " bytes) to " + remote);
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(data);
                }
            } catch (Exception e) {
                System.err.println("[PackHost-HTTP] Error serving " + path + ": " + e.getMessage());
                try {
                    exchange.sendResponseHeaders(500, -1);
                } catch (Exception ignored) {
                }
            } finally {
                exchange.close();
            }
        });

        // Use a cached thread pool so concurrent downloads don't block each other
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(1); // give at most 1s for in-flight requests
        }
    }

    /**
     * Register a pack's raw ZIP bytes so they can be served.
     * Can be called from any thread.
     */
    public void registerPack(UUID id, byte[] zipData) {
        packData.put(id, zipData);
    }
}
