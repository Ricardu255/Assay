package io.assay.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "assay.server.store=target/server-api-test/store",
            "assay.server.output=target/server-api-test/runs"
        })
class RunApiTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void submitsAnEchoRunAndReadsBackTheStoredCases() throws Exception {
        Path cases = Path.of(getClass().getResource("/cases/echo-cases.jsonl").toURI());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("adapter", "io.assay.examples.EchoAdapter");
        request.put("cases", cases.toString());
        request.put("suite", "smoke");
        HttpResponse<String> created = send("POST", "/api/runs", Json.write(request));
        assertEquals(200, created.statusCode(), created.body());
        Map<String, Object> summary = Json.parseObject(created.body());
        String runId = String.valueOf(summary.get("run_id"));
        assertFalse(runId.isBlank());
        assertEquals(0, ((Number) summary.get("hard_failures")).intValue());
        assertTrue(summary.containsKey("report_path"));

        HttpResponse<String> fetched = send("GET", "/api/runs/" + runId, null);
        assertEquals(200, fetched.statusCode(), fetched.body());
        HttpResponse<String> stored = send("GET", "/api/runs/" + runId + "/cases", null);
        assertEquals(200, stored.statusCode());
        assertTrue(stored.body().contains("SERVER-001"));
    }

    @Test
    void rejectsARequestWithoutCases() throws Exception {
        HttpResponse<String> response =
                send("POST", "/api/runs", "{\"adapter\":\"io.assay.examples.EchoAdapter\"}");
        assertEquals(400, response.statusCode());
    }

    @Test
    void rejectsAnUnsafeRunId() throws Exception {
        Path cases = Path.of(getClass().getResource("/cases/echo-cases.jsonl").toURI());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("adapter", "io.assay.examples.EchoAdapter");
        request.put("cases", cases.toString());
        request.put("run_id", "../escape");
        assertEquals(400, send("POST", "/api/runs", Json.write(request)).statusCode());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json");
        HttpRequest request = "POST".equals(method)
                ? builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build()
                : builder.GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
