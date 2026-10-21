package io.assay.http;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.assay.cli.AssayCli;
import io.assay.cli.AdapterLoader;
import io.assay.json.Json;
import io.assay.model.EvalCase;
import io.assay.model.ProjectAdapter;
import io.assay.model.RunContext;
import io.assay.rules.Rules;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpProjectAdapterTest {
    @TempDir Path root;
    private HttpServer server;
    private String endpoint;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent", exchange -> {
            calls.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
            respond(exchange, 200, "{\"data\":{\"answer\":\"hello agent\"},\"route\":\"ECHO\"}");
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stop() { server.stop(0); }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private Map<String, Object> config(String path) {
        return new LinkedHashMap<>(Map.of("type", "http", "endpoint", endpoint + path, "output_path", "data.answer",
                "hard_gates", List.of(Map.of("name", "answer", "actual", "final_output", "operator", "contains", "expected", "keyword"))));
    }

    private Path write(Map<String, Object> config) throws IOException {
        Path file = root.resolve("agent.http.json");
        Files.writeString(file, Json.write(config));
        return file;
    }

    private EvalCase evalCase() { return new EvalCase("C1", Map.of("message", "你好"), Map.of("keyword", "hello"), "direct"); }
    private RunContext context(double timeout) { return new RunContext("run1", "smoke", "online", 1, timeout); }

    @Test void jsonAdapterSendsExactPayloadAndMapsResponseWithoutJavaFactory() throws IOException {
        ProjectAdapter adapter = AdapterLoader.loadProjectAdapter(write(config("/agent")).toString(), null);
        var trace = adapter.readTrace().read(adapter.callAgent().call(evalCase(), context(3)), evalCase());
        assertEquals(Map.of("message", "你好"), Json.parseObject(body.get()));
        assertEquals("hello agent", trace.finalOutput());
        assertEquals("ECHO", trace.fields().get("route"));
        assertTrue(trace.traceId().startsWith("run1:C1:1:"));
        assertEquals("HTTP", trace.events().get(0).module());
        assertTrue(trace.events().get(0).durationMs() > 0);
        assertTrue(Rules.evaluateRule(adapter.hardGates().get(0), "hard", evalCase(), trace).passed());
        assertThrows(IllegalArgumentException.class, () -> adapter.readTrace().read(Map.of(), evalCase()));
    }

    @Test void secretsComeFromEnvironmentAndNeverEnterTrace() throws IOException {
        Map<String, Object> config = config("/agent");
        config.put("bearer_token_env", "AGENT_API_KEY");
        config.put("headers_env", Map.of("X-API-Key", "CUSTOM_KEY"));
        Path file = write(config);
        ProjectAdapter adapter = HttpProjectAdapter.load(file, name -> name.equals("AGENT_API_KEY") ? "secret-token" : "custom-secret");
        var trace = adapter.readTrace().read(adapter.callAgent().call(evalCase(), context(3)), evalCase());
        assertEquals("Bearer secret-token", authorization.get());
        assertEquals("custom-secret", apiKey.get());
        assertFalse(Json.write(trace.toJson()).contains("secret"));
        assertFalse(Files.readString(file).contains("secret-token"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> HttpProjectAdapter.load(file, name -> null)).getMessage().matches(".*(AGENT_API_KEY|CUSTOM_KEY).*"));
        assertFalse(assertThrows(IllegalArgumentException.class, () -> HttpProjectAdapter.load(file, name -> "secret\ninvalid")).getMessage().contains("secret"));
    }

    @Test void suppliedTraceIsPreservedWithoutDoubleCountingHttpBoundary() throws IOException {
        server.createContext("/trace", exchange -> respond(exchange, 200,
                "{\"data\":{\"answer\":\"hello\"},\"id\":\"provider-123\",\"steps\":[{\"module\":\"tool\",\"action\":\"search\",\"duration_ms\":12,\"status\":\"error\",\"error\":\"not found\"}]}"));
        Map<String, Object> config = config("/trace");
        config.put("trace_id_path", "id"); config.put("events_path", "steps");
        ProjectAdapter adapter = HttpProjectAdapter.load(write(config));
        var trace = adapter.readTrace().read(adapter.callAgent().call(evalCase(), context(3)), evalCase());
        assertEquals("provider-123", trace.traceId());
        assertEquals(1, trace.events().size());
        assertEquals("tool", trace.events().get(0).module());
        assertEquals(12, trace.events().get(0).durationMs());
        assertEquals("not found", trace.events().get(0).error());
    }

    @Test void errorsAreActionableWithoutLeakingServerBodyAndRedirectsAreNotFollowed() throws IOException {
        server.createContext("/failure", exchange -> respond(exchange, 401, "secret diagnostic body"));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", endpoint + "/agent");
            respond(exchange, 307, "redirect");
        });
        server.createContext("/invalid", exchange -> respond(exchange, 200, "private non-json content"));
        for (String path : List.of("/failure", "/redirect", "/invalid")) {
            ProjectAdapter adapter = HttpProjectAdapter.load(write(config(path)));
            String message = assertThrows(IllegalStateException.class, () -> adapter.callAgent().call(evalCase(), context(3))).getMessage();
            assertFalse(message.contains("secret")); assertFalse(message.contains("private"));
            assertTrue(message.contains(path.equals("/invalid") ? "JSON object" : path.equals("/failure") ? "401" : "307"));
        }
        assertEquals(0, calls.get());
    }

    @Test void deadlineAlsoCoversDelayedResponseBody() throws IOException {
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try {
                Thread.sleep(600);
                exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        ProjectAdapter adapter = HttpProjectAdapter.load(write(config("/slow")));
        long started = System.nanoTime();
        assertTrue(assertThrows(IllegalStateException.class, () -> adapter.callAgent().call(evalCase(), context(0.1))).getMessage().contains("timed out"));
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2000);
    }

    @Test void oversizedResponseIsCancelled() throws IOException {
        server.createContext("/huge", exchange -> {
            try { respond(exchange, 200, "x".repeat(8 * 1024 * 1024 + 1)); }
            catch (IOException cancelled) { exchange.close(); }
        });
        ProjectAdapter adapter = HttpProjectAdapter.load(write(config("/huge")));
        assertTrue(assertThrows(IllegalStateException.class, () -> adapter.callAgent().call(evalCase(), context(5))).getMessage().contains("8 MiB"));
    }

    @Test void badMappingOrConfigurationFailsClearlyBeforeRequest() throws IOException {
        Map<String, Object> config = config("/agent");
        config.put("output_path", "missing.answer");
        ProjectAdapter adapter = HttpProjectAdapter.load(write(config));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> adapter.readTrace().read(adapter.callAgent().call(evalCase(), context(3)), evalCase())).getMessage().contains("output_path"));
        for (String endpoint : List.of("file:///secret", "http://user:password@localhost/", "https://localhost/#fragment")) {
            Map<String, Object> invalid = config("/agent"); invalid.put("endpoint", endpoint);
            Path file = write(invalid);
            assertThrows(IllegalArgumentException.class, () -> HttpProjectAdapter.load(file));
        }
        Map<String, Object> invalid = config("/agent");
        invalid.put("hard_gates", List.of(Map.of("name", "bad", "actual", "final_output", "expected", "keyword", "operator", "typo")));
        Path file = write(invalid);
        assertThrows(IllegalArgumentException.class, () -> HttpProjectAdapter.load(file));
        assertThrows(IllegalArgumentException.class, () -> AdapterLoader.loadProjectAdapter(file.toString(), "some.Class"));
    }

    @Test void cliWritesPassAndFailReportsUsingOnlyJsonConfiguration() throws IOException {
        Path config = write(config("/agent"));
        for (String expected : List.of("hello", "missing-keyword")) {
            Path cases = root.resolve("cases.jsonl"), output = root.resolve(expected);
            Files.writeString(cases, Json.write(Map.of("id", "C1", "input", Map.of("message", "hello"), "expected", Map.of("keyword", expected))) + "\n");
            assertEquals(expected.equals("hello") ? 0 : 1, AssayCli.run(new String[]{"run", "--adapter", config.toString(), "--cases", cases.toString(),
                    "--suite", "http-smoke", "--output", output.toString(), "--store", root.resolve("store").toString()}));
            try (var files = Files.walk(output)) { assertTrue(files.anyMatch(path -> path.getFileName().toString().equals("results.json"))); }
        }
        assertEquals(2, calls.get());
    }
}
