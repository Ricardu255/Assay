package io.assay.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.assay.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serves a browser dashboard on IPv4 loopback, with GET-only report APIs. */
public final class DashboardServer implements AutoCloseable {
    private final HttpServer server;
    private final ArtifactCatalog catalog;
    private final ExecutorService executor;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    public DashboardServer(Path output, int port) throws IOException {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        catalog = new ArtifactCatalog(output);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), 32);
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "assay-dashboard");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    public Path output() {
        return catalog.root();
    }

    public void await() throws InterruptedException {
        stopped.await();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String host = exchange.getRequestHeaders().getFirst("Host");
            String port = ":" + server.getAddress().getPort();
            if (!("127.0.0.1" + port).equalsIgnoreCase(host) && !("localhost" + port).equalsIgnoreCase(host)) {
                sendJson(exchange, 403, Map.of("error", "unsupported host"));
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                sendJson(exchange, 405, Map.of("error", "the dashboard is read-only"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            try {
                if ("/api/catalog".equals(path)) {
                    sendJson(exchange, 200, catalog.list());
                } else if ("/api/rule-history".equals(path)) {
                    Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
                    int limit = query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : 20;
                    sendJson(exchange, 200, catalog.ruleHistory(query.get("rule"), query.get("suite"), limit));
                } else if (path.startsWith("/api/artifacts/")) {
                    sendJson(exchange, 200, catalog.read(path.substring("/api/artifacts/".length())));
                } else if (path.startsWith("/api/audit-patch/")) {
                    sendJson(exchange, 200, catalog.readAuditPatch(path.substring("/api/audit-patch/".length())));
                } else if (Map.of("/", "index.html", "/index.html", "index.html", "/app.js", "app.js", "/dashboard-core.js", "dashboard-core.js", "/style.css", "style.css", "/theme.js", "theme.js", "/theme.css", "theme.css").containsKey(path)) {
                    String name = "/".equals(path) ? "index.html" : path.substring(1);
                    try (InputStream input = DashboardServer.class.getResourceAsStream("/io/assay/web/" + name)) {
                        if (input == null) {
                            throw new IOException("dashboard resource missing: " + name);
                        }
                        String mime = name.endsWith(".js") ? "text/javascript" : name.endsWith(".css") ? "text/css" : "text/html";
                        send(exchange, 200, mime, input.readAllBytes());
                    }
                } else {
                    sendJson(exchange, 404, Map.of("error", "not found"));
                }
            } catch (NoSuchFileException error) {
                sendJson(exchange, 404, Map.of("error", "artifact not found"));
            } catch (IllegalArgumentException error) {
                sendJson(exchange, 400, Map.of("error", error.getMessage() == null ? "invalid artifact" : error.getMessage()));
            } catch (IOException | RuntimeException error) {
                sendJson(exchange, 500, Map.of("error", "could not read report artifacts"));
            }
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return values;
        }
        for (String part : raw.split("&")) {
            int separator = part.indexOf('=');
            String key = separator < 0 ? part : part.substring(0, separator);
            String value = separator < 0 ? "" : part.substring(separator + 1);
            values.put(
                    java.net.URLDecoder.decode(key, StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, "application/json", Json.write(value).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String mime, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", mime + "; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                        + "connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0);
            executor.shutdownNow();
            stopped.countDown();
        }
    }
}
