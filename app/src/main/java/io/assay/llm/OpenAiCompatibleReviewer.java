package io.assay.llm;

import io.assay.json.Json;
import io.assay.model.CheckResult;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.selection.TestSelectionService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal OpenAI-compatible chat-completions client.
 *
 * <p>Optional by design: with no {@code ASSAY_MODEL} set, {@link #fromEnvironment()} returns
 * {@code null} and the deterministic layers run unchanged. Nothing this client produces is
 * authoritative — it returns candidate analysis only.
 */
public record OpenAiCompatibleReviewer(
        String baseUrl,
        String apiKey,
        String model,
        double timeoutSeconds,
        double temperature,
        String provider,
        Integer maxTokens)
        implements LlmReviewer, TestSelectionService.JsonReviewer {

    /** Reads the client configuration from the environment. */
    public static OpenAiCompatibleReviewer fromEnvironment() {
        String model = env("ASSAY_MODEL");
        if (model.isEmpty()) {
            return null;
        }
        String rawMaxTokens = env("ASSAY_MAX_TOKENS");
        return new OpenAiCompatibleReviewer(
                stripTrailingSlash(
                        env("ASSAY_BASE_URL").isEmpty() ? "https://api.openai.com/v1" : env("ASSAY_BASE_URL")),
                env("ASSAY_API_KEY"),
                model,
                parseDouble(env("ASSAY_LLM_TIMEOUT"), 60),
                parseDouble(env("ASSAY_TEMPERATURE"), 0),
                env("ASSAY_PROVIDER").isEmpty() ? "openai-compatible" : env("ASSAY_PROVIDER"),
                rawMaxTokens.isEmpty() ? null : parseInt(rawMaxTokens, 0));
    }

    @Override
    public Map<String, Object> analyze(
            EvalCase evalCase, List<NormalizedTrace> traces, List<CheckResult> checks, boolean collectFewShot) {
        List<Object> traceRows = new ArrayList<>(traces.size());
        for (NormalizedTrace trace : traces) {
            traceRows.add(trace.toJson());
        }
        List<Object> failed = new ArrayList<>();
        for (CheckResult check : checks) {
            if (!check.passed()) {
                failed.add(check.toJson());
            }
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("case", evalCase.toJson());
        evidence.put("traces", traceRows);
        evidence.put("failed_checks", failed);
        evidence.put("collect_few_shot", collectFewShot);
        String prompt =
                "You are an evidence-bound Agent evaluation reviewer. "
                        + "Use only the supplied case, traces, and failed checks. "
                        + "Return JSON with keys: summary, suspected_modules, suggestions, consistency, "
                        + "few_shot_candidate. Suggestions are review candidates, never final decisions. "
                        + "Set few_shot_candidate to null unless collect_few_shot is true and the run is clearly successful.\n\n"
                        + Json.write(evidence);
        return requestJson(prompt);
    }

    public Map<String, Object> requestJson(String prompt) {
        return requestJsonWithUsage(prompt).result();
    }

    /** Returns both the parsed body and the provider's token usage, if it reported any. */
    public Response requestJsonWithUsage(String prompt) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", temperature);
        if (maxTokens != null) {
            requestBody.put("max_tokens", maxTokens);
        }

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofMillis((long) (timeoutSeconds * 1000)))
                        .POST(HttpRequest.BodyPublishers.ofString(Json.write(requestBody), StandardCharsets.UTF_8));
        if (!apiKey.isEmpty()) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new LlmException("model request failed: " + error, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new LlmException("model request interrupted", error);
        }
        if (response.statusCode() >= 400) {
            throw new LlmException("model request failed with HTTP " + response.statusCode());
        }
        Map<String, Object> payload = Json.parseObject(response.body());
        String content = extractContent(payload);
        Map<String, Object> result;
        try {
            result = Json.parseObjectLoose(content);
        } catch (RuntimeException parseError) {
            result = new LinkedHashMap<>();
            result.put("summary", content);
            result.put("parse_error", "model did not return valid JSON");
        }
        return new Response(result, asMap(payload.get("usage")));
    }

    /** A parsed model response plus its usage block. */
    public record Response(Map<String, Object> result, Map<String, Object> usage) {
    }

    private static String extractContent(Map<String, Object> payload) {
        Object choices = payload.get("choices");
        if (choices instanceof List<?> items && !items.isEmpty()) {
            Object message = asMap(items.get(0)).get("message");
            Object content = asMap(message).get("content");
            if (content != null) {
                return String.valueOf(content);
            }
        }
        throw new LlmException("model response has no choices[0].message.content");
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    /** Raised when the provider cannot be reached or answers with something unusable. */
    public static class LlmException extends RuntimeException {

        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
