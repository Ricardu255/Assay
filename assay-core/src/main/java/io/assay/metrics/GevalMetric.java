package io.assay.metrics;

import io.assay.model.MetricResult;
import io.assay.support.Immutable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * G-Eval-lite: turn one criterion into checkable steps, then score the evidence against them.
 *
 * <p>Two model calls at most: the first generates steps once per metric instance (skipped when the
 * configuration supplies them), the second scores each case. The result keeps the raw 0-10 score,
 * the normalized score, the steps, and the judge's reason so a person can audit the verdict.
 */
public final class GevalMetric implements Metric {

    /** Default metric name. */
    public static final String NAME = "geval";

    private final String name;
    private final String criteria;
    private final List<String> configuredSteps;
    private final double threshold;
    private final boolean strict;
    private final LlmJudge judge;
    private volatile List<String> generatedSteps = List.of();

    /** Creates an auto-step, non-strict metric at the given threshold. */
    public GevalMetric(String criteria, double threshold, LlmJudge judge) {
        this(NAME, criteria, List.of(), threshold, false, judge);
    }

    /** Creates an auto-step, non-strict metric with a custom name. */
    public GevalMetric(String name, String criteria, double threshold, LlmJudge judge) {
        this(name, criteria, List.of(), threshold, false, judge);
    }

    /**
     * Creates a G-Eval metric.
     *
     * @param name metric name used in reports and cache keys
     * @param criteria the one-sentence criterion the judge scores against
     * @param steps predetermined evaluation steps; empty means "generate once from the criterion"
     * @param threshold minimum normalized score required to pass
     * @param strict when true the judge must answer 0 or 1 and the threshold is 1.0
     * @param judge model used for step generation and scoring
     */
    public GevalMetric(
            String name,
            String criteria,
            List<String> steps,
            double threshold,
            boolean strict,
            LlmJudge judge) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("geval requires a non-empty name");
        }
        if (criteria == null || criteria.isBlank()) {
            throw new IllegalArgumentException("geval requires a criterion");
        }
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("geval threshold must be finite and between 0 and 1");
        }
        if (strict && threshold != 1.0) {
            throw new IllegalArgumentException("strict geval requires a threshold of 1.0");
        }
        this.name = name.trim();
        this.criteria = criteria.trim();
        this.configuredSteps = Immutable.list(steps);
        this.threshold = threshold;
        this.strict = strict;
        this.judge = judge;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String defaultLevel() {
        return "soft";
    }

    @Override
    public String fingerprint() {
        if (judge == null) {
            return null;
        }
        return "geval-v1;name=" + name
                + ";criteria=" + criteria
                + ";steps=" + (configuredSteps.isEmpty() ? "auto" : configuredSteps)
                + ";threshold=" + threshold
                + ";strict=" + strict
                + ";model=" + judge.model();
    }

    @Override
    public MetricResult measure(MetricContext context) {
        if (judge == null) {
            return MetricResult.error(name, "geval requires an LLM judge; set ASSAY_MODEL");
        }
        Steps steps;
        try {
            steps = resolveSteps();
        } catch (RuntimeException error) {
            return MetricResult.error(name, "cannot generate evaluation steps: " + messageOf(error));
        }
        String evidence = TraceRenderer.render(context.evalCase(), context.trace());
        LlmJudge.Response response;
        try {
            response = judge.evaluate(scoringPrompt(steps.values(), evidence));
        } catch (RuntimeException error) {
            return MetricResult.error(name, "judge call failed: " + messageOf(error));
        }
        Double raw = rawScore(response.result());
        if (raw == null) {
            return MetricResult.error(name, "judge returned no usable score");
        }
        if (strict && raw != 0.0 && raw != 1.0) {
            return MetricResult.error(name, "strict judge returned " + raw + "; expected 0 or 1");
        }
        double score = strict ? raw : raw / 10.0;
        String reason = text(response.result().get("reason"));
        if (reason.isEmpty()) {
            reason = strict ? "judge answered " + raw.intValue() : "judge scored " + raw + "/10";
        }
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("criteria", criteria);
        breakdown.put("steps", steps.values());
        breakdown.put("strict", strict);
        breakdown.put("raw_score", raw);
        breakdown.put("model", judge.model());

        MetricResult result = score >= threshold
                ? MetricResult.pass(name, score, threshold, reason)
                : MetricResult.fail(name, score, threshold, reason);
        return result.withDetails(breakdown, List.of())
                .withUsage(
                        steps.inputTokens() + response.inputTokens(),
                        steps.outputTokens() + response.outputTokens(),
                        sum(steps.costUsd(), response.costUsd()),
                        judge.model());
    }

    private Steps resolveSteps() {
        if (!configuredSteps.isEmpty()) {
            return new Steps(configuredSteps, 0, 0, null);
        }
        List<String> cached = generatedSteps;
        if (!cached.isEmpty()) {
            return new Steps(cached, 0, 0, null);
        }
        synchronized (this) {
            if (!generatedSteps.isEmpty()) {
                return new Steps(generatedSteps, 0, 0, null);
            }
            LlmJudge.Response response = judge.evaluate(stepsPrompt());
            List<String> parsed = parseSteps(response.result());
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("judge returned no evaluation steps");
            }
            generatedSteps = Immutable.list(parsed);
            return new Steps(generatedSteps, response.inputTokens(), response.outputTokens(), response.costUsd());
        }
    }

    private String stepsPrompt() {
        return "You are designing evaluation steps for an LLM judge.\n"
                + "Criterion:\n" + criteria + "\n\n"
                + "The subject is an AI agent's trace and final answer. Write 2 to 5 concrete, checkable "
                + "steps that decide whether the criterion is met. Each step must refer to observable "
                + "evidence in the trace or the final output.\n"
                + "Return JSON only: {\"steps\": [\"...\", \"...\"]}";
    }

    private String scoringPrompt(List<String> steps, String evidence) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are scoring an AI agent's output against one criterion.\n")
                .append("Criterion:\n").append(criteria).append("\n\n")
                .append("Evaluation steps:\n");
        for (int index = 0; index < steps.size(); index++) {
            prompt.append(index + 1).append(". ").append(steps.get(index)).append('\n');
        }
        prompt.append("\nEvidence:\n").append(evidence).append('\n');
        if (strict) {
            prompt.append("\nAnswer 1 only when every step passes; answer 0 otherwise. ")
                    .append("Return JSON only: {\"score\": 0 or 1, ")
                    .append("\"reason\": \"short explanation citing the failing step\"}");
        } else {
            prompt.append("\nScore from 0 (fails completely) to 10 (fully meets the criterion). ")
                    .append("Return JSON only: {\"score\": <number 0-10>, ")
                    .append("\"reason\": \"short explanation citing the failing step\"}");
        }
        return prompt.toString();
    }

    private static List<String> parseSteps(Map<String, Object> result) {
        Object raw = result.get("steps");
        if (!(raw instanceof List<?>)) {
            raw = result.get("evaluation_steps");
        }
        if (!(raw instanceof List<?> rows)) {
            return List.of();
        }
        List<String> steps = new ArrayList<>();
        for (Object row : rows) {
            String step = text(row);
            if (!step.isEmpty()) {
                steps.add(step);
            }
            if (steps.size() == 8) {
                break;
            }
        }
        return List.copyOf(steps);
    }

    private static Double rawScore(Map<String, Object> result) {
        Object raw = result.get("score");
        if (raw == null) {
            raw = result.get("overall_score");
        }
        double score;
        if (raw instanceof Number number) {
            score = number.doubleValue();
        } else if (raw instanceof String text) {
            try {
                score = Double.parseDouble(text.trim());
            } catch (NumberFormatException error) {
                return null;
            }
        } else {
            return null;
        }
        return Double.isFinite(score) && score >= 0 && score <= 10 ? score : null;
    }

    private static BigDecimal sum(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.add(right);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String messageOf(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record Steps(List<String> values, long inputTokens, long outputTokens, BigDecimal costUsd) {
    }
}
