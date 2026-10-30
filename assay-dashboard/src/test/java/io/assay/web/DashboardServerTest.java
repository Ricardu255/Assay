package io.assay.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.json.Json;
import io.assay.runner.JobStatus;
import io.assay.store.file.FileJobStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DashboardServerTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void servesReportsAndAssetsWithoutChangingReports(@TempDir Path root) throws Exception {
        Path run = root.resolve("nested/run-1/results.json");
        Files.createDirectories(run.getParent());
        String json = Json.write(Map.of("run_id", "run-1", "status", "failed", "suite", "smoke",
                "case_count", 1, "hard_failures", 1, "soft_warnings", 0, "results", List.of()));
        Files.writeString(run, json);
        byte[] before = Files.readAllBytes(run);
        Files.writeString(root.resolve("unrelated.json"), "{}");
        Files.writeString(root.resolve("evolution.json"), "not JSON");

        try (DashboardServer server = new DashboardServer(root, 0)) {
            URI base = server.uri();
            HttpResponse<String> page = get(base);
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("评测工作台"));
            assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").contains("object-src 'none'"));
            assertEquals(200, get(base.resolve("app.js")).statusCode());
            assertEquals(200, get(base.resolve("dashboard-core.js")).statusCode());
            assertEquals(200, get(base.resolve("theme.js")).statusCode());
            assertEquals(200, get(base.resolve("theme.css")).statusCode());
            assertEquals(200, get(base.resolve("style.css")).statusCode());

            Map<String, Object> catalog = Json.parseObject(get(base.resolve("api/catalog")).body());
            List<?> items = (List<?>) catalog.get("items");
            assertEquals(1, items.size());
            assertEquals(1, ((List<?>) catalog.get("warnings")).size());
            Map<?, ?> item = (Map<?, ?>) items.get(0);
            assertEquals("run", item.get("kind"));
            assertEquals("run-1", item.get("name"));
            assertEquals("failed", item.get("status"));
            assertEquals(json, get(base.resolve("api/artifacts/" + item.get("id"))).body());

            HttpRequest post = HttpRequest.newBuilder(base.resolve("api/catalog"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            assertEquals(405, CLIENT.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, get(base.resolve("not-a-page")).statusCode());
            assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(run)));
        }
    }

    @Test
    void queueApiSubmitsAndCancelsJobs(@TempDir Path root) throws Exception {
        Path storePath = root.resolve("store");
        try (FileJobStore jobs = new FileJobStore(storePath);
                DashboardServer server = new DashboardServer(root.resolve("runs"), 0, jobs, storePath.toString())) {
            URI base = server.uri();
            Map<String, Object> empty = Json.parseObject(get(base.resolve("api/jobs")).body());
            assertEquals(0, ((List<?>) empty.get("jobs")).size());

            HttpRequest submit = HttpRequest.newBuilder(base.resolve("api/jobs"))
                    .header("X-Assay-Request", "1")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of(
                            "adapter", "io.assay.examples.EchoAdapter",
                            "suite", "ui-queue",
                            "cases", "examples/cases.example.jsonl"))))
                    .build();
            HttpResponse<String> created = CLIENT.send(submit, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, created.statusCode(), created.body());
            String jobId = String.valueOf(Json.parseObject(created.body()).get("job_id"));
            assertEquals(JobStatus.QUEUED, jobs.find(jobId).orElseThrow().status());

            HttpRequest cancel = HttpRequest.newBuilder(base.resolve("api/jobs/" + jobId + "/cancel"))
                    .header("X-Assay-Request", "1")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            assertEquals(200, CLIENT.send(cancel, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(JobStatus.CANCELLED, jobs.find(jobId).orElseThrow().status());
        }
    }

    @Test
    void queueApiIsDisabledWithoutAWorkerStore(@TempDir Path root) throws Exception {
        try (DashboardServer server = new DashboardServer(root, 0)) {
            assertEquals(503, get(server.uri().resolve("api/jobs")).statusCode());
        }
    }

    @Test
    void catalogRefreshesChangedReportSummaries(@TempDir Path root) throws Exception {
        Path report = root.resolve("results.json");
        Files.writeString(report, Json.write(Map.of(
                "run_id", "run-1", "status", "failed", "suite", "smoke")));
        ArtifactCatalog catalog = new ArtifactCatalog(root);
        assertEquals("failed", item(catalog, "run-1").get("status"));

        Files.writeString(report, Json.write(Map.of(
                "run_id", "run-1", "status", "passed", "suite", "smoke", "case_count", 2)));
        Files.setLastModifiedTime(report, FileTime.fromMillis(System.currentTimeMillis() + 2_000));
        Map<?, ?> refreshed = item(catalog, "run-1");
        assertEquals("passed", refreshed.get("status"));
        assertEquals(2L, refreshed.get("case_count"));
    }

    @Test
    void catalogRecognizesCompareReports(@TempDir Path root) throws Exception {
        Path file = root.resolve("compare/compare.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.write(Map.of(
                "dataset", "suite",
                "baseline", Map.of("run_id", "base", "case_count", 2, "hard_failures", 0, "soft_warning_count", 0),
                "candidate", Map.of("run_id", "cand", "case_count", 2, "hard_failures", 1, "soft_warning_count", 0),
                "gate", Map.of("status", "BLOCK", "violations", List.of()))));

        ArtifactCatalog catalog = new ArtifactCatalog(root);
        Map<?, ?> item = item(catalog, "base -> cand");
        assertEquals("compare", item.get("kind"));
        assertEquals("block", item.get("status"));
        assertEquals(2L, item.get("case_count"));
        assertEquals(1L, item.get("hard_failures"));
        assertEquals("suite", item.get("suite"));
    }

    private static Map<?, ?> item(ArtifactCatalog catalog, String name) throws Exception {
        List<?> items = (List<?>) catalog.list().get("items");
        return items.stream()
                .map(value -> (Map<?, ?>) value)
                .filter(value -> name.equals(value.get("name")))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void ruleHistoryReturnsLatestPointsForSuite(@TempDir Path root) throws Exception {
        Path first = root.resolve("runs/run-1/results.json");
        Path second = root.resolve("runs/run-2/results.json");
        Path other = root.resolve("runs/other/results.json");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.createDirectories(other.getParent());
        Files.writeString(first, Json.write(Map.of("run_id", "run-1", "suite", "smoke", "source", "online",
                "case_count", 2, "rule_stats", Map.of("route", Map.of("level", "hard", "evaluated", 2,
                        "passed", 1, "pass_rate", 0.5, "missing", 0)))));
        Files.writeString(second, Json.write(Map.of("run_id", "run-2", "suite", "smoke", "source", "online",
                "case_count", 2, "rule_stats", Map.of("route", Map.of("level", "hard", "evaluated", 2,
                        "passed", 2, "pass_rate", 1.0, "missing", 0)))));
        Files.writeString(other, Json.write(Map.of("run_id", "other", "suite", "regression", "source", "online",
                "case_count", 2, "rule_stats", Map.of("route", Map.of("level", "hard", "evaluated", 2,
                        "passed", 0, "pass_rate", 0.0, "missing", 0)))));
        Files.setLastModifiedTime(first, java.nio.file.attribute.FileTime.fromMillis(1000));
        Files.setLastModifiedTime(second, java.nio.file.attribute.FileTime.fromMillis(2000));
        Files.setLastModifiedTime(other, java.nio.file.attribute.FileTime.fromMillis(3000));

        try (DashboardServer server = new DashboardServer(root, 0)) {
            URI base = server.uri();
            Map<String, Object> history = Json.parseObject(get(base.resolve("api/rule-history?rule=route&suite=smoke&limit=20")).body());
            List<?> points = (List<?>) history.get("points");
            assertEquals(2, points.size());
            assertEquals(2L, history.get("matched"));
            assertEquals("run-1", ((Map<?, ?>) points.get(0)).get("run_id"));
            assertEquals("run-2", ((Map<?, ?>) points.get(1)).get("run_id"));
            assertEquals(1.0, ((Map<?, ?>) points.get(1)).get("pass_rate"));
            assertEquals(0, ((List<?>) Json.parseObject(get(base.resolve("api/rule-history?rule=missing&suite=smoke")).body()).get("points")).size());
            assertEquals(400, get(base.resolve("api/rule-history")).statusCode());
            assertEquals(400, get(base.resolve("api/rule-history?rule=route&limit=nope")).statusCode());
        }
    }

    @Test
    void refusesTraversalAndUnsupportedFiles(@TempDir Path root) throws Exception {
        Path report = root.resolve("results.json");
        Files.writeString(report, "{}");
        ArtifactCatalog catalog = new ArtifactCatalog(root);
        assertThrows(IllegalArgumentException.class, () -> catalog.read(id("../results.json")));
        assertThrows(IllegalArgumentException.class, () -> catalog.read(id("other.json")));
        assertThrows(IllegalArgumentException.class, () -> catalog.read(id("C:/outside/results.json")));
        assertThrows(IllegalArgumentException.class, () -> catalog.read("!"));
        assertFalse(((List<?>) catalog.list().get("items")).isEmpty());
    }

    @Test
    void invalidPortAndNondirectoryRootFailClearly(@TempDir Path root) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new DashboardServer(root, -1));
        Path file = root.resolve("file");
        Files.writeString(file, "x");
        assertThrows(IllegalArgumentException.class, () -> new ArtifactCatalog(file));
    }

    @Test
    void auditPatchIsBoundToItsReportAndVerifiedBeforeServing(@TempDir Path root) throws Exception {
        String patch = "diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1 +1 @@\n-old\n+new\n";
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(patch.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(root.resolve("scope_audit.json"), Json.write(Map.of("diff_sha256", digest)));
        Files.writeString(root.resolve("diff.patch"), patch);
        Files.writeString(root.resolve("results.json"), "{}");
        ArtifactCatalog catalog = new ArtifactCatalog(root);
        assertThrows(IllegalArgumentException.class, () -> catalog.readAuditPatch(id("results.json")));
        assertThrows(IllegalArgumentException.class, () -> catalog.read(id("diff.patch")));
        assertThrows(IllegalArgumentException.class, () -> catalog.readAuditPatch(id("../scope_audit.json")));
        try (DashboardServer server = new DashboardServer(root, 0)) {
            URI uri = server.uri().resolve("api/audit-patch/" + id("scope_audit.json"));
            HttpResponse<String> response = get(uri);
            assertEquals(200, response.statusCode());
            assertEquals(patch, Json.parseObject(response.body()).get("text"));
            assertEquals(true, Json.parseObject(response.body()).get("verified"));
            Files.writeString(root.resolve("diff.patch"), "tampered");
            assertEquals(400, get(uri).statusCode());
        }
    }

    private static HttpResponse<String> get(URI uri) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String id(String path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8));
    }
}
