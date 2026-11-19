package io.assay.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.EvalCase;
import io.assay.model.MetricResult;
import io.assay.model.MetricStatus;
import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import io.assay.testing.ScriptedJudge;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

class GevalMetricTest {

    @Test
    void generatesStepsOnceThenScoresEveryCase() {
        ScriptedJudge judge = new ScriptedJudge(List.of(
                Map.of("steps", List.of("check the claim", "check the answer")),
                Map.of("score", 7, "reason", "the answer cites an unsupported claim"),
                Map.of("score", 9, "reason", "every claim is supported")));
        GevalMetric metric = new GevalMetric("groundedness", "answer is grounded", 0.8, judge);

        MetricResult first = metric.measure(context());
        MetricResult second = metric.measure(context());

        assertEquals(MetricStatus.FAIL, first.status());
        assertEquals(0.7, first.score());
        assertEquals("the answer cites an unsupported claim", first.reason());
        assertEquals(List.of("check the claim", "check the answer"), first.breakdown().get("steps"));
        assertEquals(7.0, first.breakdown().get("raw_score"));
        assertEquals(20, first.inputTokens());
        assertEquals(10, first.outputTokens());
        assertEquals(0, first.costUsd().compareTo(new java.math.BigDecimal("0.002")));
        assertEquals("scripted-judge", first.model());

        assertEquals(MetricStatus.PASS, second.status());
        assertEquals(0.9, second.score());
        assertEquals(3, judge.calls());
        assertTrue(judge.prompts().get(0).contains("designing evaluation steps"));
        assertTrue(judge.prompts().get(1).contains("Evidence:"));
    }

    @Test
    void usesProvidedStepsWithoutAnExtraCall() {
        ScriptedJudge judge = new ScriptedJudge(List.of(Map.of("score", 9, "reason", "fine")));
        GevalMetric metric =
                new GevalMetric("quality", "answer is clear", List.of("check clarity"), 0.8, false, judge);

        MetricResult result = metric.measure(context());

        assertEquals(MetricStatus.PASS, result.status());
        assertEquals(0.9, result.score());
        assertEquals(1, judge.calls());
    }

    @Test
    void strictModeAcceptsOnlyZeroOrOne() {
        ScriptedJudge judge = new ScriptedJudge(List.of(
                Map.of("score", 1, "reason", "passes"),
                Map.of("score", 0, "reason", "fails")));
        GevalMetric metric = new GevalMetric("safe", "no unsafe output", List.of("check safety"), 1.0, true, judge);

        assertEquals(MetricStatus.PASS, metric.measure(context()).status());
        assertEquals(MetricStatus.FAIL, metric.measure(context()).status());
    }

    @Test
    void strictModeRejectsAGradedAnswer() {
        ScriptedJudge judge = new ScriptedJudge(List.of(Map.of("score", 7, "reason", "partial")));
        GevalMetric metric = new GevalMetric("safe", "no unsafe output", List.of("check safety"), 1.0, true, judge);

        MetricResult result = metric.measure(context());

        assertEquals(MetricStatus.ERROR, result.status());
        assertTrue(result.reason().contains("expected 0 or 1"), result.reason());
    }

    @Test
    void invalidScoresBecomeErrors() {
        ScriptedJudge judge = new ScriptedJudge(List.of(Map.of("score", "not a number", "reason", "?")));
        GevalMetric metric = new GevalMetric("quality", "clear", List.of("check clarity"), 0.8, false, judge);

        assertEquals(MetricStatus.ERROR, metric.measure(context()).status());
        assertFalse(metric.measure(context()).reason().isBlank());
    }

    @Test
    void missingStepsAndMissingJudgeAreErrors() {
        GevalMetric noJudge = new GevalMetric("quality", "clear", 0.8, null);
        assertEquals(MetricStatus.ERROR, noJudge.measure(context()).status());
        assertTrue(noJudge.measure(context()).reason().contains("ASSAY_MODEL"));

        ScriptedJudge empty = new ScriptedJudge(List.of(Map.of("score", 9)));
        GevalMetric metric = new GevalMetric("quality", "clear", 0.8, empty);
        assertEquals(MetricStatus.ERROR, metric.measure(context()).status());
        assertTrue(metric.measure(context()).reason().contains("no evaluation steps"));
    }

    @Test
    void fingerprintCoversConfigurationAndModel() {
        ScriptedJudge judge = new ScriptedJudge(List.of(Map.of("score", 1)));
        GevalMetric metric = new GevalMetric("quality", "clear", 0.8, judge);

        String fingerprint = metric.fingerprint();

        assertTrue(fingerprint.contains("criteria=clear"), fingerprint);
        assertTrue(fingerprint.contains("model=scripted-judge"), fingerprint);
    }

    private static MetricContext context() {
        NormalizedTrace trace = new NormalizedTrace(
                "trace",
                "the answer",
                List.of(new TraceEvent("agent", "reply", "ok", 1, null, Map.of("agent_name", "planner"))),
                Map.of());
        return new MetricContext(new EvalCase("case", Map.of("message", "hi"), Map.of(), "default"), trace);
    }
}
