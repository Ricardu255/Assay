package io.assay.http;

import io.assay.json.Json;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.Rule;
import io.assay.model.RunContext;
import io.assay.model.TraceEvent;
import io.assay.rules.RuleConfig;
import io.assay.rules.ValueOps;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Calls a synchronous JSON HTTP agent without requiring a compiled domain adapter. */
public final class HttpProjectAdapter {
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final Set<String> OPTIONS = Set.of("type", "name", "endpoint", "output_path",
            "trace_id_path", "events_path", "bearer_token_env", "headers_env", "hard_gates", "soft_quality");
    private final URI endpoint;
    private final String outputPath;
    private final String traceIdPath;
    private final String eventsPath;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpProjectAdapter(Map<String, Object> config, Function<String, String> environment) {
        for (String key : config.keySet()) {
            if (!OPTIONS.contains(key)) throw new IllegalArgumentException("unknown HTTP configuration option: " + key);
        }
        if (!"http".equals(config.get("type"))) throw new IllegalArgumentException("HTTP configuration requires type: http");
        try {
            endpoint = URI.create(text(config, "endpoint", null));
            if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                    || endpoint.getHost() == null || endpoint.getRawUserInfo() != null || endpoint.getFragment() != null
                    || endpoint.getPort() == 0 || endpoint.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("endpoint must be an HTTP(S) URL without credentials or fragment");
        }
        outputPath = path(config, "output_path", "output");
        traceIdPath = path(config, "trace_id_path", "trace_id");
        eventsPath = config.containsKey("events_path") ? path(config, "events_path", null) : null;
        if (config.containsKey("headers_env")) {
            Map<String, Object> variables = object(config.get("headers_env"), "headers_env");
            for (String header : variables.keySet()) {
                headers.put(header, secret(text(variables, header, null), environment));
            }
        }
        if (config.containsKey("bearer_token_env")) {
            if (headers.keySet().stream().anyMatch("Authorization"::equalsIgnoreCase)) {
                throw new IllegalArgumentException("configure Authorization using either bearer_token_env or headers_env");
            }
            headers.put("Authorization", "Bearer " + secret(text(config, "bearer_token_env", null), environment));
        }
        try {
            HttpRequest.Builder check = HttpRequest.newBuilder(endpoint);
            headers.forEach(check::header);
            if (headers.keySet().stream().anyMatch(key -> Set.of("content-type", "accept").contains(key.toLowerCase(java.util.Locale.ROOT)))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("headers_env contains an invalid, restricted, or reserved header");
        }
    }

    public static ProjectAdapter load(Path file) {
        return load(file, System::getenv);
    }

    static ProjectAdapter load(Path file, Function<String, String> environment) {
        Map<String, Object> config;
        try {
            if (Files.size(file) > 1024 * 1024) throw new IllegalArgumentException("HTTP configuration exceeds 1 MiB");
            config = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read HTTP configuration: " + file, error);
        }
        // Validate rules before constructing a client or attempting a network request.
        List<Rule> hard = RuleConfig.parse(config.get("hard_gates"), "hard_gates");
        if (hard.isEmpty()) throw new IllegalArgumentException("HTTP configuration requires at least one hard_gates rule");
        List<Rule> soft = RuleConfig.parse(config.get("soft_quality"), "soft_quality");
        String name = text(config, "name", "http-agent");
        HttpProjectAdapter adapter = new HttpProjectAdapter(config, environment);
        return new ProjectAdapter(name, adapter::call, adapter::read, hard, soft);
    }

    private Object call(EvalCase evalCase, RunContext context) {
        Duration timeout = Duration.ofNanos((long) Math.max(1, Math.min(Long.MAX_VALUE, context.timeoutSeconds() * 1_000_000_000d)));
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(evalCase.payload()), StandardCharsets.UTF_8));
        headers.forEach(request::header);
        long started = System.nanoTime();
        var pending = client.sendAsync(request.build(), ignored -> new LimitedBody());
        try {
            HttpResponse<byte[]> response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Agent HTTP status " + response.statusCode() + "; check endpoint and authentication");
            }
            Map<String, Object> data;
            try {
                data = Json.parseObject(new String(response.body(), StandardCharsets.UTF_8));
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("Agent response must be a JSON object (streaming/SSE is not supported)");
            }
            return new Response(data, context.runId() + ":" + evalCase.caseId() + ":" + context.attempt() + ":" + java.util.UUID.randomUUID(),
                    (System.nanoTime() - started) / 1_000_000d, response.statusCode());
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent HTTP request interrupted");
        } catch (TimeoutException timeoutError) {
            pending.cancel(true);
            throw new IllegalStateException("Agent HTTP request timed out");
        } catch (ExecutionException networkError) {
            if (networkError.getCause() instanceof java.net.http.HttpTimeoutException) {
                throw new IllegalStateException("Agent HTTP request timed out");
            }
            // Avoid putting credentials, request headers, or server error bodies into reports.
            throw new IllegalStateException("Agent HTTP request failed (network, timeout, or response exceeds 8 MiB)");
        }
    }

    private NormalizedTrace read(Object handle, EvalCase evalCase) {
        if (!(handle instanceof Response response)) {
            throw new IllegalArgumentException("HTTP configuration requires --source online; use a Java adapter for offline traces");
        }
        Object output = ValueOps.readPath(response.data(), outputPath);
        if (output == ValueOps.MISSING) throw new IllegalArgumentException("HTTP response is missing output_path: " + outputPath);
        Object traceId = ValueOps.readPath(response.data(), traceIdPath);
        String id = traceId == ValueOps.MISSING || traceId == null || String.valueOf(traceId).isBlank()
                ? response.localId() : String.valueOf(traceId);
        List<TraceEvent> events = new ArrayList<>();
        // This measures the HTTP boundary; internal steps are included only when actually supplied.
        if (eventsPath == null) {
            events.add(new TraceEvent("HTTP", "POST", "ok", response.durationMs(), null, Map.of("status_code", response.status())));
        } else {
            Object supplied = ValueOps.readPath(response.data(), eventsPath);
            if (!(supplied instanceof List<?> rows)) throw new IllegalArgumentException("events_path must resolve to an array");
            for (Object row : rows) events.add(TraceEvent.fromDict(object(row, "HTTP trace event")));
        }
        return new NormalizedTrace(id, output, events, response.data(), Map.of(), "agent", "http-agent", "unknown",
                Map.of("http", Map.of("duration_ms", response.durationMs(), "status_code", response.status(),
                        "trace_id_source", id.equals(response.localId()) ? "assay" : "agent")));
    }

    private record Response(Map<String, Object> data, String localId, double durationMs, int status) {}

    private static String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.getOrDefault(key, fallback);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " must be a non-empty string");
        return text;
    }

    private static String path(Map<String, Object> map, String key, String fallback) {
        String value = text(map, key, fallback);
        if (value.length() > 512 || value.chars().anyMatch(Character::isISOControl)
                || java.util.Arrays.stream(value.split("\\.", -1)).anyMatch(part -> part.isEmpty()
                    || (part.matches("[0-9]+") && (part.length() > 9)))) {
            throw new IllegalArgumentException(key + " must be a dot-separated path, such as data.answer or choices.0.text");
        }
        return value;
    }

    private static String secret(String variable, Function<String, String> environment) {
        if (!variable.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("invalid authentication environment variable name");
        String value = environment.apply(variable);
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("set a non-empty authentication environment variable: " + variable);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException(label + " must be an object");
        return (Map<String, Object>) value;
    }

    /** Enforce the response limit while receiving, before accumulating an unbounded body. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long received;
        private boolean ended;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        public void onNext(List<ByteBuffer> buffers) {
            if (ended) return;
            for (ByteBuffer buffer : buffers) received += buffer.remaining();
            if (received > MAX_RESPONSE_BYTES) {
                ended = true; subscription.cancel(); delegate.onError(new IOException("HTTP response exceeds 8 MiB"));
            } else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { if (!ended) { ended = true; delegate.onError(error); } }
        public void onComplete() { if (!ended) { ended = true; delegate.onComplete(); } }
    }
}
