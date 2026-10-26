package io.assay.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.assay.json.Json;
import io.assay.runner.Job;
import io.assay.runner.JobStore;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serves a browser dashboard on IPv4 loopback, with GET-only report APIs. */
public final class DashboardServer implements AutoCloseable {
    private final HttpServer server;
    private final ArtifactCatalog catalog;
    private final JobStore jobs;
    private final String storeReference;
    private final ExecutorService executor;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    public DashboardServer(Path output, int port) throws IOException {
        this(output, port, null, null);
    }

    public DashboardServer(Path output, int port, JobStore jobs, String storeReference) throws IOException {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        catalog = new ArtifactCatalog(output);
        this.jobs = jobs;
        this.storeReference = storeReference;
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
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(method)) {
                handlePost(exchange, path);
                return;
            }
            if (!"GET".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, POST");
                sendJson(exchange, 405, Map.of("error", "unsupported method"));
                return;
            }
            try {
                if ("/api/jobs".equals(path)) {
                    if (jobs == null) {
                        sendJson(exchange, 503, Map.of(
                                "error", "queue disabled; start serve with --worker"));
                    } else {
                        sendJson(exchange, 200, jobList(exchange.getRequestURI().getRawQuery()));
                    }
                } else if ("/api/catalog".equals(path)) {
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

    private void handlePost(HttpExchange exchange, String path) throws IOException {
        if (!"/api/jobs".equals(path) && !path.startsWith("/api/jobs/")) {
            exchange.getResponseHeaders().set("Allow", "GET");
            sendJson(exchange, 405, Map.of("error", "unsupported method"));
            return;
        }
        if (jobs == null) {
            sendJson(exchange, 503, Map.of("error", "queue disabled; start serve with --worker"));
            return;
        }
        if (!"1".equals(exchange.getRequestHeaders().getFirst("X-Assay-Request"))) {
            sendJson(exchange, 403, Map.of("error", "missing local request header"));
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            sendJson(exchange, 415, Map.of("error", "Content-Type must be application/json"));
            return;
        }
        try {
            if ("/api/jobs".equals(path)) {
                Map<String, Object> request = Json.parseObject(readBody(exchange));
                int attempts = maxAttempts(request);
                Job job = jobs.submit(jobPayload(request), attempts);
                sendJson(exchange, 201, jobView(job));
                return;
            }
            if (path.startsWith("/api/jobs/") && path.endsWith("/cancel")) {
                String id = path.substring("/api/jobs/".length(), path.length() - "/cancel".length());
                if (id.isBlank() || id.contains("/")) {
                    throw new IllegalArgumentException("invalid job id");
                }
                boolean cancelled = jobs.cancel(id);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("job_id", id);
                response.put("cancelled", cancelled);
                jobs.find(id).ifPresent(job -> response.put("status", job.status().name()));
                sendJson(exchange, cancelled ? 200 : 409, response);
                return;
            }
            sendJson(exchange, 404, Map.of("error", "not found"));
        } catch (IllegalArgumentException error) {
            sendJson(exchange, 400, Map.of("error", error.getMessage() == null ? "invalid request" : error.getMessage()));
        } catch (IOException | RuntimeException error) {
            sendJson(exchange, 500, Map.of("error", "could not update job queue"));
        }
    }

    private Map<String, Object> jobPayload(Map<String, Object> request) {
        Set<String> allowed = Set.of(
                "adapter", "adapter_class", "suite", "cases", "output", "source", "workers",
                "retries", "timeout", "use_llm", "collect_few_shot", "run_identity", "run_id",
                "max_attempts");
        for (String key : request.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("unknown job field: " + key);
            }
        }
        String adapter = required(request, "adapter");
        String suite = required(request, "suite");
        String cases = required(request, "cases");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("adapter", adapter);
        if (request.get("adapter_class") != null) {
            payload.put("adapter_class", request.get("adapter_class"));
        }
        payload.put("suite", suite);
        payload.put("cases", cases);
        payload.put("store", storeReference);
        payload.put("output", request.get("output") == null ? catalog.root().toString() : request.get("output"));
        payload.put("source", request.getOrDefault("source", "online"));
        payload.put("workers", request.getOrDefault("workers", 1));
        payload.put("retries", request.getOrDefault("retries", 0));
        payload.put("timeout", request.getOrDefault("timeout", 30));
        payload.put("use_llm", Boolean.TRUE.equals(request.get("use_llm")));
        payload.put("collect_few_shot", Boolean.TRUE.equals(request.get("collect_few_shot")));
        if (request.get("run_identity") != null) {
            payload.put("run_identity", request.get("run_identity"));
        }
        String runId = request.get("run_id") == null ? "" : String.valueOf(request.get("run_id")).trim();
        payload.put("run_id", runId.isEmpty() ? "queued-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) : runId);
        return payload;
    }

    private static int maxAttempts(Map<String, Object> request) {
        Object value = request.get("max_attempts");
        int attempts = value instanceof Number number ? number.intValue() : 3;
        return Math.max(1, Math.min(10, attempts));
    }

    private static String required(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return String.valueOf(value).trim();
    }

    private Map<String, Object> jobList(String rawQuery) {
        Map<String, String> query = query(rawQuery);
        int limit = query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : 20;
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Job job : jobs.list(limit)) {
            rows.add(jobView(job));
        }
        return Map.of("jobs", rows);
    }

    private static Map<String, Object> jobView(Job job) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("job_id", job.id());
        row.put("status", job.status().name());
        row.put("run_id", job.payload().get("run_id"));
        row.put("suite", job.payload().get("suite"));
        row.put("adapter", job.payload().get("adapter"));
        row.put("attempts", job.attempts());
        row.put("max_attempts", job.maxAttempts());
        row.put("lease_owner", job.leaseOwner());
        row.put("lease_expires_at", job.leaseExpiresAt() == null ? null : job.leaseExpiresAt().toString());
        row.put("cancel_requested", job.cancelRequested());
        row.put("created_at", job.createdAt().toString());
        row.put("started_at", job.startedAt() == null ? null : job.startedAt().toString());
        row.put("finished_at", job.finishedAt() == null ? null : job.finishedAt().toString());
        row.put("error", job.error());
        return row;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream input = exchange.getRequestBody()) {
            body = input.readNBytes(64 * 1024 + 1);
        }
        if (body.length > 64 * 1024) {
            throw new IllegalArgumentException("request body too large");
        }
        return new String(body, StandardCharsets.UTF_8);
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
            if (jobs != null) {
                jobs.close();
            }
            stopped.countDown();
        }
    }
}
