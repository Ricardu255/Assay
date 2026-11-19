package io.assay.model;

import io.assay.json.Jsonable;
import io.assay.support.Immutable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The outcome of one metric on one case.
 *
 * <p>A metric result is richer than a {@link CheckResult}: it keeps the score and threshold that
 * produced the verdict, the reason a human can read, a structured breakdown, and the per-unit
 * evidence (a claim, a tool call, a step) that justifies the score. Token and cost fields are
 * optional because not every metric calls a model.
 */
public record MetricResult(
        String name,
        MetricStatus status,
        double score,
        double threshold,
        String reason,
        Map<String, Object> breakdown,
        List<Map<String, Object>> evidence,
        long inputTokens,
        long outputTokens,
        BigDecimal costUsd,
        String model,
        boolean cached)
        implements Jsonable {

    public MetricResult {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("metric result requires a non-empty name");
        }
        if (status == null) {
            throw new IllegalArgumentException("metric result requires a status");
        }
        Values.rate("threshold", threshold);
        if (!Double.isFinite(score) && !Double.isNaN(score)) {
            throw new IllegalArgumentException("metric score must be finite or NaN");
        }
        if (Double.isFinite(score)) {
            Values.rate("score", score);
        }
        if ((status == MetricStatus.PASS || status == MetricStatus.FAIL) && !Double.isFinite(score)) {
            throw new IllegalArgumentException(status + " metric result requires a finite score");
        }
        if ((status == MetricStatus.SKIP || status == MetricStatus.ERROR || status == MetricStatus.NOT_EVALUATED)
                && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(status + " metric result requires a reason");
        }
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("metric token counts must be non-negative");
        }
        if (costUsd != null && costUsd.signum() < 0) {
            throw new IllegalArgumentException("metric cost must be non-negative");
        }
        reason = reason == null ? "" : reason;
        breakdown = Immutable.map(breakdown);
        evidence = evidence == null ? List.of() : copyEvidence(evidence);
        name = name.trim();
    }

    /** A result that ran and met its threshold. */
    public static MetricResult pass(String name, double score, double threshold, String reason) {
        return new MetricResult(name, MetricStatus.PASS, score, threshold, reason, Map.of(), List.of(), 0, 0, null,
                null, false);
    }

    /** A result that ran and missed its threshold. */
    public static MetricResult fail(String name, double score, double threshold, String reason) {
        return new MetricResult(name, MetricStatus.FAIL, score, threshold, reason, Map.of(), List.of(), 0, 0, null,
                null, false);
    }

    /** A result that had no valid input to measure; it is reported but excluded from pass rates. */
    public static MetricResult skip(String name, double threshold, String reason) {
        return new MetricResult(name, MetricStatus.SKIP, Double.NaN, threshold, reason, Map.of(), List.of(), 0, 0,
                null, null, false);
    }

    /** A result whose measurement itself failed. */
    public static MetricResult error(String name, String reason) {
        return new MetricResult(name, MetricStatus.ERROR, Double.NaN, 0, reason, Map.of(), List.of(), 0, 0, null,
                null, false);
    }

    /** A result that never ran because the case stopped first. */
    public static MetricResult notEvaluated(String name, double threshold, String reason) {
        return new MetricResult(name, MetricStatus.NOT_EVALUATED, Double.NaN, threshold, reason, Map.of(), List.of(),
                0, 0, null, null, false);
    }

    /** Returns a copy carrying the structured explanation of the score. */
    public MetricResult withDetails(Map<String, Object> breakdown, List<Map<String, Object>> evidence) {
        return new MetricResult(name, status, score, threshold, reason, breakdown, evidence, inputTokens,
                outputTokens, costUsd, model, cached);
    }

    /** Returns a copy carrying the model usage that produced this result. */
    public MetricResult withUsage(long inputTokens, long outputTokens, BigDecimal costUsd, String model) {
        return new MetricResult(name, status, score, threshold, reason, breakdown, evidence, inputTokens, outputTokens,
                costUsd, model, cached);
    }

    /** Returns a copy marked as served from the metric cache. */
    public MetricResult cached(boolean cached) {
        return new MetricResult(name, status, score, threshold, reason, breakdown, evidence, inputTokens, outputTokens,
                costUsd, model, cached);
    }

    /**
     * Returns a cache-hit copy: same verdict and explanation, but zero measured usage.
     *
     * <p>A cache hit costs nothing and calls no model, so keeping the original token counts would make
     * run-level cost totals overstate spend even though the budget ledger correctly skips it.
     */
    public MetricResult asCached() {
        return new MetricResult(name, status, score, threshold, reason, breakdown, evidence, 0, 0,
                BigDecimal.ZERO, model, true);
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("status", status.name().toLowerCase(Locale.ROOT));
        result.put("threshold", threshold);
        if (Double.isFinite(score)) {
            result.put("score", score);
        }
        result.put("reason", reason);
        result.put("breakdown", breakdown);
        result.put("evidence", evidence);
        result.put("input_tokens", inputTokens);
        result.put("output_tokens", outputTokens);
        result.put("cost_usd", costUsd == null ? null : costUsd.doubleValue());
        result.put("model", model);
        result.put("cached", cached);
        return result;
    }

    /** Reads a result back from its JSON projection. */
    public static MetricResult fromJson(Map<String, Object> value) {
        String name = String.valueOf(value.getOrDefault("name", "")).trim();
        MetricStatus status;
        try {
            status = MetricStatus.valueOf(
                    String.valueOf(value.getOrDefault("status", "ERROR")).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unknown metric status: " + value.get("status"), error);
        }
        double score = value.get("score") == null ? Double.NaN : Values.toDouble(value.get("score"), Double.NaN);
        double threshold = value.get("threshold") == null ? 0.5 : Values.toDouble(value.get("threshold"), 0.5);
        List<Map<String, Object>> evidence = new ArrayList<>();
        if (value.get("evidence") instanceof List<?> rows) {
            for (Object row : rows) {
                evidence.add(Immutable.map(Values.asMap(row)));
            }
        }
        return new MetricResult(
                name,
                status,
                score,
                threshold,
                String.valueOf(value.getOrDefault("reason", "")),
                Immutable.map(Values.asMap(value.get("breakdown"))),
                evidence,
                longValue(value.get("input_tokens")),
                longValue(value.get("output_tokens")),
                costOf(value.get("cost_usd")),
                value.get("model") == null ? null : String.valueOf(value.get("model")),
                Boolean.TRUE.equals(value.get("cached")));
    }

    private static List<Map<String, Object>> copyEvidence(List<Map<String, Object>> source) {
        List<Map<String, Object>> copy = new ArrayList<>(source.size());
        for (Map<String, Object> row : source) {
            copy.add(Immutable.map(row));
        }
        return Immutable.list(copy);
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static BigDecimal costOf(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            return new BigDecimal(text.trim());
        }
        return null;
    }
}
