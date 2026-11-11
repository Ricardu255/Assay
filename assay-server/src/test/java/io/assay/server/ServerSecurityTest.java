package io.assay.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "assay.server.store=target/server-security-test/store",
            "assay.server.output=target/server-security-test/runs",
            "assay.server.auth-tokens=test-token"
        })
class ServerSecurityTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void pingAndHealthStayPublic() throws Exception {
        assertEquals(200, send("/api/ping", null).statusCode());
        assertEquals(200, send("/actuator/health", null).statusCode());
    }

    @Test
    void rejectsApiCallsWithoutAToken() throws Exception {
        HttpResponse<String> response = send("/api/runs", null);
        assertEquals(401, response.statusCode(), response.body());
        assertTrue(response.body().contains("missing or invalid API token"), response.body());
    }

    @Test
    void rejectsAWrongToken() throws Exception {
        HttpResponse<String> response = send("/api/runs", "Bearer wrong-token");
        assertEquals(401, response.statusCode());
    }

    @Test
    void acceptsABearerToken() throws Exception {
        assertEquals(200, send("/api/runs", "Bearer test-token").statusCode());
    }

    @Test
    void acceptsAnApiKeyHeader() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/runs"))
                .header("X-API-Key", "test-token")
                .GET()
                .build();
        assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private HttpResponse<String> send(String path, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
