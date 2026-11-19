package io.assay.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.assay.model.EvalCase;
import io.assay.model.MetricResult;
import io.assay.model.NormalizedTrace;
import io.assay.model.TraceEvent;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

class MetricCacheKeyTest {

    @Test
    void isStableForTheSameCaseTraceAndConfiguration() {
        MetricContext context = context(trace("lookup"));
        Metric metric = new ToolPermissionMetric();

        assertEquals(MetricCacheKey.of(context, metric), MetricCacheKey.of(context, metric));
        assertEquals(64, MetricCacheKey.of(context, metric).length());
    }

    @Test
    void changesWhenTheTraceChanges() {
        Metric metric = new ToolPermissionMetric();

        assertNotEquals(
                MetricCacheKey.of(context(trace("lookup")), metric),
                MetricCacheKey.of(context(trace("shell")), metric));
    }

    @Test
    void changesWhenTheMetricConfigurationChanges() {
        MetricContext context = context(trace("lookup"));

        assertNotEquals(
                MetricCacheKey.of(context, new ToolPermissionMetric()),
                MetricCacheKey.of(context, new ToolPermissionMetric(List.of(), List.of("shell"))));
    }

    @Test
    void returnsNullWhenTheMetricOptsOutOfCaching() {
        Metric uncached = new Metric() {
            @Override
            public String name() {
                return "uncached";
            }

            @Override
            public MetricResult measure(MetricContext context) {
                return MetricResult.pass("uncached", 1, 0.5, "");
            }
        };

        assertNull(MetricCacheKey.of(context(trace("lookup")), uncached));
    }

    private static MetricContext context(NormalizedTrace trace) {
        return new MetricContext(new EvalCase("case", Map.of("input", "x"), Map.of(), "default"), trace);
    }

    private static NormalizedTrace trace(String tool) {
        return new NormalizedTrace(
                "trace",
                "out",
                List.of(new TraceEvent("tool", "call_start", "ok", 0, null, Map.of("tool_call_name", tool))),
                Map.of());
    }
}
