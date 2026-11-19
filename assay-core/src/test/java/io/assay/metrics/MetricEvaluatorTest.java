package io.assay.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.CheckResult;
import io.assay.model.EvalCase;
import io.assay.model.MetricResult;
import io.assay.model.MetricStatus;
import io.assay.model.NormalizedTrace;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class MetricEvaluatorTest {

    private static final EvalCase CASE = new EvalCase("case", Map.of(), Map.of(), "default");
    private static final NormalizedTrace TRACE = new NormalizedTrace("trace", "output", List.of(), Map.of());

    @Test
    void returnsAnEmptyOutcomeWhenNothingIsConfigured() {
        MetricEvaluator evaluator = new MetricEvaluator(List.of());

        MetricEvaluator.Outcome outcome = evaluator.evaluate(CASE, TRACE, List.of(TRACE));

        assertTrue(evaluator.isEmpty());
        assertTrue(outcome.isEmpty());
        assertEquals(List.of(), outcome.checks());
    }

    @Test
    void mapsPassAndFailOntoChecksUsingTheDeclaredLevel() {
        MetricEvaluator evaluator = new MetricEvaluator(List.of(
                new StubMetric("hard_pass", "hard", MetricResult.pass("hard_pass", 0.9, 0.8, "ok")),
                new StubMetric("soft_fail", "soft", MetricResult.fail("soft_fail", 0.4, 0.5, "low"))));

        MetricEvaluator.Outcome outcome = evaluator.evaluate(CASE, TRACE, List.of(TRACE));

        assertEquals(2, outcome.results().size());
        CheckResult hard = outcome.checks().get(0);
        assertEquals("metric", hard.layer());
        assertEquals("hard", hard.level());
        assertEquals("hard_pass", hard.name());
        assertTrue(hard.passed());
        assertEquals(0.9, hard.actual());
        assertEquals(0.8, hard.expected());

        CheckResult soft = outcome.checks().get(1);
        assertEquals("soft", soft.level());
        assertFalse(soft.passed());
        assertEquals("low", soft.message());
    }

    @Test
    void skipAndNotEvaluatedStayVisibleButProduceNoCheck() {
        MetricEvaluator evaluator = new MetricEvaluator(List.of(
                new StubMetric("skipped", "soft", MetricResult.skip("skipped", 0.5, "no data")),
                new StubMetric("later", "soft",
                        MetricResult.notEvaluated("later", 0.5, "case stopped"))));

        MetricEvaluator.Outcome outcome = evaluator.evaluate(CASE, TRACE, List.of(TRACE));

        assertEquals(2, outcome.results().size());
        assertEquals(MetricStatus.SKIP, outcome.results().get(0).status());
        assertEquals(List.of(), outcome.checks());
    }

    @Test
    void aThrowingMetricBecomesAnErrorAndAHardCheck() {
        Metric throwing = new Metric() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public MetricResult measure(MetricContext context) {
                throw new IllegalStateException("judge unavailable");
            }
        };

        MetricEvaluator.Outcome outcome =
                new MetricEvaluator(List.of(throwing)).evaluate(CASE, TRACE, List.of(TRACE));

        assertEquals(MetricStatus.ERROR, outcome.results().get(0).status());
        assertEquals("judge unavailable", outcome.results().get(0).reason());
        CheckResult check = outcome.checks().get(0);
        assertEquals("hard", check.level());
        assertFalse(check.passed());
    }

    @Test
    void anUnknownLevelFallsBackToSoft() {
        MetricEvaluator.Outcome outcome = new MetricEvaluator(List.of(
                new StubMetric("odd", "critical", MetricResult.fail("odd", 0, 1, "bad"))))
                .evaluate(CASE, TRACE, List.of(TRACE));

        assertEquals("soft", outcome.checks().get(0).level());
    }

    @Test
    void reusesCachedResultsAndMarksThem() {
        CountingMetric metric = new CountingMetric();
        MetricEvaluator evaluator = new MetricEvaluator(List.of(metric), new MemoryCache());

        MetricResult first = evaluator.evaluate(CASE, TRACE, List.of(TRACE)).results().get(0);
        MetricResult second = evaluator.evaluate(CASE, TRACE, List.of(TRACE)).results().get(0);

        assertFalse(first.cached());
        assertTrue(second.cached());
        assertEquals(0, second.inputTokens());
        assertEquals(0, second.outputTokens());
        assertEquals(0, second.costUsd().compareTo(java.math.BigDecimal.ZERO));
        assertEquals(1, metric.calls);
    }

    @Test
    void doesNotCacheErrors() {
        int[] calls = {0};
        Metric broken = new Metric() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public String fingerprint() {
                return "v1";
            }

            @Override
            public MetricResult measure(MetricContext context) {
                calls[0]++;
                throw new IllegalStateException("unavailable");
            }
        };
        MetricEvaluator evaluator = new MetricEvaluator(List.of(broken), new MemoryCache());

        evaluator.evaluate(CASE, TRACE, List.of(TRACE));
        evaluator.evaluate(CASE, TRACE, List.of(TRACE));

        assertEquals(2, calls[0]);
    }

    private static final class CountingMetric implements Metric {

        private int calls;

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public String fingerprint() {
            return "v1";
        }

        @Override
        public MetricResult measure(MetricContext context) {
            calls++;
            return MetricResult.pass("counting", 1, 0.5, "ok")
                    .withUsage(10, 5, new java.math.BigDecimal("0.01"), "scripted");
        }
    }

    private static final class MemoryCache implements MetricCache {

        private final Map<String, MetricResult> entries = new LinkedHashMap<>();

        @Override
        public Optional<MetricResult> lookup(String key) {
            return Optional.ofNullable(entries.get(key));
        }

        @Override
        public void store(String key, MetricResult result) {
            entries.put(key, result);
        }
    }

    private record StubMetric(String name, String level, MetricResult result) implements Metric {

        @Override
        public String defaultLevel() {
            return level;
        }

        @Override
        public MetricResult measure(MetricContext context) {
            return result;
        }
    }
}
