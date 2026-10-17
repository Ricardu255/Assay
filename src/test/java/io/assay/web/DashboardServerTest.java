package io.assay.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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

    private static HttpResponse<String> get(URI uri) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String id(String path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8));
    }
}
