package io.assay.metrics;

import io.assay.support.Immutable;
import java.math.BigDecimal;
import java.util.Map;

/**
 * A model used as a grader by semantic metrics.
 *
 * <p>The contract is intentionally narrow: one prompt in, one JSON object out, plus the usage the
 * provider reported. Metrics own their prompts and scoring rules; the judge only transports them, so
 * a different provider can be swapped without touching metric code.
 */
public interface LlmJudge {

    /** Provider model name, used in results and cache fingerprints. */
    String model();

    /** Sends one prompt and returns the parsed JSON object with usage. */
    Response evaluate(String prompt);

    /** A parsed judge response and the usage it cost. */
    record Response(
            Map<String, Object> result,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd,
            String model) {

        public Response {
            if (result == null) {
                throw new IllegalArgumentException("judge response requires a result object");
            }
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("judge token counts must be non-negative");
            }
            result = Immutable.map(result);
            model = model == null ? "unknown" : model;
        }

        /** Total tokens reported by the provider. */
        public long totalTokens() {
            return inputTokens + outputTokens;
        }
    }
}
