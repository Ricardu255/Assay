package io.assay.llm;

import io.assay.metrics.LlmJudge;
import io.assay.model.Values;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Adapts the OpenAI-compatible chat client to the metric judge contract.
 *
 * <p>The client stays in the runtime module; the core only sees {@link LlmJudge}. Configuration is
 * shared with the reviewer ({@code ASSAY_MODEL}, {@code ASSAY_BASE_URL}, {@code ASSAY_API_KEY}), so a
 * project that already uses {@code --use-llm} needs no new settings.
 */
public final class OpenAiCompatibleJudge implements LlmJudge {

    private final OpenAiCompatibleReviewer reviewer;

    public OpenAiCompatibleJudge(OpenAiCompatibleReviewer reviewer) {
        if (reviewer == null) {
            throw new IllegalArgumentException("judge requires a configured model");
        }
        this.reviewer = reviewer;
    }

    /** Returns a judge when {@code ASSAY_MODEL} is configured, otherwise null. */
    public static OpenAiCompatibleJudge fromEnvironment() {
        OpenAiCompatibleReviewer reviewer = OpenAiCompatibleReviewer.fromEnvironment();
        return reviewer == null ? null : new OpenAiCompatibleJudge(reviewer);
    }

    /** Builds a judge from explicit settings, used by CLI overrides and tests. */
    public static OpenAiCompatibleJudge from(
            String baseUrl, String apiKey, String model, double timeoutSeconds) {
        return new OpenAiCompatibleJudge(new OpenAiCompatibleReviewer(
                baseUrl, apiKey, model, timeoutSeconds, 0, "openai-compatible", null));
    }

    @Override
    public String model() {
        return reviewer.model();
    }

    @Override
    public Response evaluate(String prompt) {
        OpenAiCompatibleReviewer.Response response = reviewer.requestJsonWithUsage(prompt);
        Map<String, Object> usage = Values.asMap(response.usage());
        return new Response(
                response.result(),
                tokens(usage, "prompt_tokens", "input_tokens"),
                tokens(usage, "completion_tokens", "output_tokens"),
                cost(usage.get("cost_usd")),
                reviewer.model());
    }

    private static long tokens(Map<String, Object> usage, String primary, String alternate) {
        Object value = usage.containsKey(primary) ? usage.get(primary) : usage.get(alternate);
        return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }

    private static BigDecimal cost(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException malformed) {
                return null;
            }
        }
        return null;
    }
}
