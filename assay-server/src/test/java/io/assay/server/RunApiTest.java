package io.assay.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.assay.json.Json;
import io.assay.store.file.ResultStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "assay.server.store=target/server-api-test/store",
            "assay.server.output=target/server-api-test/runs",
            "assay.server.worker-threads=1"
        })
class RunApiTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void submitsAnEchoRunAndReadsBackTheStoredCases() throws Exception {
        HttpResponse<String> created = send("POST", "/api/runs", Json.write(echoRequest()));
        assertEquals(202, created.statusCode(), created.body());
        Map<String, Object> accepted = Json.parseObject(created.body());
        String runId = String.valueOf(accepted.get("run_id"));
        assertFalse(runId.isBlank());
        assertEquals("queued", accepted.get("status"));

        Map<String, Object> finished = awaitStatus(runId, "passed");
        assertEquals(0, ((Number) finished.get("hard_failures")).intValue());
        assertTrue(finished.containsKey("report_path"), String.valueOf(finished));

        HttpResponse<String> fetched = send("GET", "/api/runs/" + runId, null);
        assertEquals(200, fetched.statusCode(), fetched.body());
        Map<String, Object> fetchedRun = Json.parseObject(fetched.body());
        assertEquals("passed", fetchedRun.get("status"));

        HttpResponse<String> stored = send("GET", "/api/runs/" + runId + "/cases", null);
        assertEquals(200, stored.statusCode());
        assertTrue(stored.body().contains("SERVER-001"));
    }

    @Test
    void listsRunsPersistedByTheStore() throws Exception {
        String runId = "persisted-" + UUID.randomUUID();
        ResultStore store = new ResultStore(Path.of("target/server-api-test/store"));
        store.startRun(runId, "io.assay.examples.EchoAdapter", "smoke", "offline", Map.of("workers", 1));
        store.finishRun(runId, "passed");

        HttpResponse<String> list = send("GET", "/api/runs", null);
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains(runId));

        HttpResponse<String> fetched = send("GET", "/api/runs/" + runId, null);
        assertEquals(200, fetched.statusCode(), fetched.body());
        Map<String, Object> run = Json.parseObject(fetched.body());
        assertEquals("passed", run.get("status"));
        assertEquals("io.assay.examples.EchoAdapter", run.get("adapter"));
    }

    @Test
    void reportsAnAdapterFailureAsAFailedRun() throws Exception {
        Map<String, Object> request = echoRequest();
        request.put("adapter", "io.assay.examples.DoesNotExist");
        HttpResponse<String> created = send("POST", "/api/runs", Json.write(request));
        assertEquals(202, created.statusCode(), created.body());
        String runId = String.valueOf(Json.parseObject(created.body()).get("run_id"));

        Map<String, Object> failed = awaitStatus(runId, "failed");
        assertTrue(failed.containsKey("error"), String.valueOf(failed));
    }

    @Test
    void resumesARunWithAnExplicitRunId() throws Exception {
        String runId = "resume-" + UUID.randomUUID();
        Map<String, Object> request = echoRequest();
        request.put("run_id", runId);
        assertEquals(202, send("POST", "/api/runs", Json.write(request)).statusCode());
        awaitStatus(runId, "passed");

        Map<String, Object> resumed = echoRequest();
        resumed.put("run_id", runId);
        resumed.put("resume", true);
        HttpResponse<String> created = send("POST", "/api/runs", Json.write(resumed));
        assertEquals(202, created.statusCode(), created.body());
        Map<String, Object> done = awaitStatus(runId, "passed");
        assertEquals(1, ((Number) done.get("case_count")).intValue(), String.valueOf(done));
        assertEquals(0, ((Number) done.get("hard_failures")).intValue(), String.valueOf(done));
    }

    @Test
    void rejectsResumeWithoutRunId() throws Exception {
        Map<String, Object> request = echoRequest();
        request.put("resume", true);
        assertEquals(400, send("POST", "/api/runs", Json.write(request)).statusCode());
    }

    @Test
    void rejectsARequestWithoutCases() throws Exception {
        HttpResponse<String> response =
                send("POST", "/api/runs", "{\"adapter\":\"io.assay.examples.EchoAdapter\"}");
        assertEquals(400, response.statusCode());
    }

    @Test
    void rejectsAnUnsafeRunId() throws Exception {
        Map<String, Object> request = echoRequest();
        request.put("run_id", "../escape");
        assertEquals(400, send("POST", "/api/runs", Json.write(request)).statusCode());
    }

    private Map<String, Object> echoRequest() throws Exception {
        Path cases = Path.of(getClass().getResource("/cases/echo-cases.jsonl").toURI());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("adapter", "io.assay.examples.EchoAdapter");
        request.put("cases", cases.toString());
        request.put("suite", "smoke");
        return request;
    }

    private Map<String, Object> awaitStatus(String runId, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        Map<String, Object> last = Map.of();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = send("GET", "/api/runs/" + runId, null);
            if (response.statusCode() == 200) {
                last = Json.parseObject(response.body());
                if (expected.equals(last.get("status"))) {
                    return last;
                }
            }
            Thread.sleep(100);
        }
        fail("run " + runId + " did not reach " + expected + ": " + last);
        return last;
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
