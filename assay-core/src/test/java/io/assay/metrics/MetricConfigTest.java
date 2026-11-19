package io.assay.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.json.Json;
import io.assay.model.EvalCase;
import io.assay.model.MetricResult;
import io.assay.model.MetricStatus;
import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import io.assay.support.AtomicFiles;
import io.assay.testing.ScriptedJudge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class MetricConfigTest {

    @Test
    void parsesAToolPermissionMetricWithOverrides() {
        List<Metric> metrics = MetricConfig.parse(Json.parseObject("""
                {"metrics":[{"type":"tool_permission","level":"soft","threshold":0.5,
                 "allowed_tools":["lookup"],"denied_tools":["shell"]}]}
                """));

        assertEquals(1, metrics.size());
        Metric metric = metrics.get(0);
        assertEquals("tool_permission", metric.name());
        assertEquals("soft", metric.defaultLevel());

        MetricResult result = metric.measure(context(List.of(tool("lookup"), tool("shell"))));
        assertEquals(MetricStatus.PASS, result.status());
        assertEquals(0.5, result.score());
    }

    @Test
    void acceptsABareArrayAndAppliesDefaults() {
        List<Metric> metrics = MetricConfig.parse(Json.parse("""
                [{"type":"tool_permission","denied_tools":["shell"]}]
                """));

        assertEquals("hard", metrics.get(0).defaultLevel());
        MetricResult result = metrics.get(0).measure(context(List.of(tool("shell"), tool("lookup"))));
        assertEquals(MetricStatus.FAIL, result.status());
        assertEquals(0.5, result.score());
    }

    @Test
    void loadsFromAFile(@TempDir Path root) {
        Path file = root.resolve("metrics.json");
        AtomicFiles.writeText(file, "{\"metrics\":[{\"type\":\"tool_permission\"}]}");

        assertEquals(1, MetricConfig.load(file).size());
    }

    @Test
    void rejectsUnknownTypesOptionsAndMalformedValues() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MetricConfig.parse(Json.parseObject("{\"metrics\":[{\"type\":\"nope\"}]}")))
                .getMessage().contains("unsupported metric type"));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MetricConfig.parse(Json.parseObject("{\"metrics\":[{\"type\":\"tool_permission\",\"x\":1}]}")))
                .getMessage().contains("unknown metric option"));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MetricConfig.parse(Json.parseObject("{\"metrics\":[{\"type\":\"tool_permission\",\"threshold\":2}]}")))
                .getMessage().contains("between 0 and 1"));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MetricConfig.parse(Json.parseObject("{\"metrics\":[{\"type\":\"tool_permission\",\"level\":\"loud\"}]}")))
                .getMessage().contains("hard or soft"));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MetricConfig.parse(Json.parseObject("{\"metrics\":[{\"type\":\"tool_permission\",\"allowed_tools\":\"lookup\"}]}")))
                .getMessage().contains("array of strings"));
    }

    @Test
    void parsesLoopDetectionWithAThreshold() {
        List<Metric> metrics = MetricConfig.parse(Json.parseObject(
                "{\"metrics\":[{\"type\":\"loop_detection\",\"threshold\":0.6}]}"));

        assertEquals("loop_detection", metrics.get(0).name());
        MetricResult result = metrics.get(0).measure(context(List.of(tool("lookup"))));
        assertEquals(MetricStatus.PASS, result.status());
    }

    @Test
    void parsesGevalWithProvidedStepsAndAName() {
        ScriptedJudge judge = new ScriptedJudge(List.of(Map.of("score", 9, "reason", "grounded")));
        List<Metric> metrics = MetricConfig.parse(Json.parseObject("""
                {"metrics":[{"type":"geval","name":"groundedness","criteria":"answer is grounded",
                 "steps":["check every claim"],"level":"soft"}]}
                """), judge);

        Metric metric = metrics.get(0);
        assertEquals("groundedness", metric.name());
        assertEquals("soft", metric.defaultLevel());
        assertEquals(MetricStatus.PASS, metric.measure(context(List.of(tool("lookup")))).status());
        assertEquals(1, judge.calls());
    }

    @Test
    void strictGevalDefaultsToOneAndAcceptsATernaryAnswer() {
        ScriptedJudge judge = new ScriptedJudge(List.of(Map.of("score", 1, "reason", "safe")));
        List<Metric> metrics = MetricConfig.parse(Json.parseObject("""
                {"metrics":[{"type":"geval","criteria":"no unsafe output","steps":["check safety"],"strict":true}]}
                """), judge);

        assertEquals(MetricStatus.PASS, metrics.get(0).measure(context(List.of())).status());
    }

    @Test
    void gevalRequiresAConfiguredJudgeAndACriterion() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MetricConfig.parse(Json.parseObject("{\"metrics\":[{\"type\":\"geval\",\"criteria\":\"x\"}]}")))
                .getMessage().contains("ASSAY_MODEL"));

        ScriptedJudge judge = new ScriptedJudge(List.of(Map.of("score", 1)));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MetricConfig.parse(Json.parseObject("{\"metrics\":[{\"type\":\"geval\"}]}"), judge))
                .getMessage().contains("criteria"));
    }

    @Test
    void rejectsANameOnADeterministicMetric() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MetricConfig.parse(Json.parseObject(
                        "{\"metrics\":[{\"type\":\"tool_permission\",\"name\":\"x\"}]}")))
                .getMessage().contains("unknown metric option: name"));
    }

    @Test
    void rejectsUnknownRootOptions() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MetricConfig.parse(Json.parseObject("{\"metric\":[]}")))
                .getMessage().contains("unknown metric config option"));
    }

    private static MetricContext context(List<TraceEvent> events) {
        NormalizedTrace trace = new NormalizedTrace("trace", "out", events, Map.of());
        return new MetricContext(new EvalCase("case", Map.of(), Map.of(), "default"), trace);
    }

    private static TraceEvent tool(String name) {
        return new TraceEvent("tool", "call_start", "ok", 0, null, Map.of("tool_call_name", name));
    }
}
