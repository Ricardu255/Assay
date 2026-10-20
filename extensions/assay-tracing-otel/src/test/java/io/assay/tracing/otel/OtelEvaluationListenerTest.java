package io.assay.tracing.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.engine.EvaluationEngine;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.TraceEvent;
import io.assay.store.ResultStore;
import io.assay.store.Results;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** OTel spans must reflect the real run/case lifecycle, including parent links and failures. */
class OtelEvaluationListenerTest {

    @Test
    void emitsRunCaseAndBatchSpans(@TempDir Path root) {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        try {
            Tracer tracer = provider.get("assay-test");
            OtelEvaluationListener listener = new OtelEvaluationListener(tracer);

            try (ResultStore store = new ResultStore(root.resolve("store"))) {
                Map<String, Object> summary = new EvaluationEngine(
                                adapter(), store, null, 1, 0, 30, false, null, listener)
                        .runSuite(cases(), "suite", "online", "otel-run", false);
                assertEquals(2, summary.get("case_count"));
            }

            List<SpanData> spans = exporter.getFinishedSpanItems();
            SpanData run = spans.stream()
                    .filter(span -> span.getName().equals("assay.run"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("otel-run", run.getAttributes().get(AttributeKey.stringKey("assay.run_id")));
            assertEquals("suite", run.getAttributes().get(AttributeKey.stringKey("assay.suite")));
            assertEquals(StatusCode.OK, run.getStatus().getStatusCode());

            List<SpanData> cases = spans.stream()
                    .filter(span -> span.getName().equals("assay.case"))
                    .toList();
            assertEquals(2, cases.size());
            for (SpanData caseSpan : cases) {
                assertEquals(run.getSpanId(), caseSpan.getParentSpanId());
                assertNotNull(caseSpan.getAttributes().get(AttributeKey.stringKey("assay.case_id")));
                assertEquals(StatusCode.OK, caseSpan.getStatus().getStatusCode());
            }
            assertTrue(spans.stream().anyMatch(span -> span.getName().equals("assay.batch")));

            List<SpanData> adapters = spans.stream()
                    .filter(span -> span.getName().equals("assay.adapter"))
                    .toList();
            List<SpanData> rules = spans.stream()
                    .filter(span -> span.getName().equals("assay.rules"))
                    .toList();
            List<SpanData> stores = spans.stream()
                    .filter(span -> span.getName().equals("assay.store"))
                    .toList();
            assertEquals(2, adapters.size());
            assertEquals(2, rules.size());
            assertEquals(1, stores.size());
            for (SpanData stage : adapters) {
                assertEquals(StatusCode.OK, stage.getStatus().getStatusCode());
                assertCaseParent(stage, cases);
            }
            for (SpanData stage : rules) {
                assertEquals(StatusCode.OK, stage.getStatus().getStatusCode());
                assertCaseParent(stage, cases);
            }
            assertEquals(run.getSpanId(), stores.get(0).getParentSpanId());
            assertEquals(2L, stores.get(0).getAttributes().get(AttributeKey.longKey("count")));
        } finally {
            provider.close();
        }
    }

    @Test
    void closesTheRunSpanWhenSavingFails(@TempDir Path root) {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        try (ResultStore delegate = new ResultStore(root.resolve("store"))) {
            Results failing = (Results) Proxy.newProxyInstance(
                    Results.class.getClassLoader(),
                    new Class<?>[] {Results.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("saveCases")) {
                            throw new IllegalStateException("disk full");
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException error) {
                            throw error.getCause();
                        }
                    });

            OtelEvaluationListener listener = new OtelEvaluationListener(provider.get("assay-test"));
            assertThrows(
                    IllegalStateException.class,
                    () -> new EvaluationEngine(adapter(), failing, null, 1, 0, 30, false, null, listener)
                            .runSuite(cases(), "suite", "online", "otel-failed", false));

            SpanData run = exporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals("assay.run"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(StatusCode.ERROR, run.getStatus().getStatusCode());
            assertEquals("failed", run.getAttributes().get(AttributeKey.stringKey("assay.status")));
            assertTrue(run.getAttributes().get(AttributeKey.stringKey("assay.error")).contains("disk full"));
        } finally {
            provider.close();
        }
    }

    private static void assertCaseParent(SpanData stage, List<SpanData> cases) {
        String caseId = stage.getAttributes().get(AttributeKey.stringKey("assay.case_id"));
        SpanData parent = cases.stream()
                .filter(candidate -> caseId.equals(candidate.getAttributes().get(AttributeKey.stringKey("assay.case_id"))))
                .findFirst()
                .orElseThrow();
        assertEquals(parent.getSpanId(), stage.getParentSpanId());
    }

    private static ProjectAdapter adapter() {
        return new ProjectAdapter(
                "otel-test",
                (evalCase, context) -> Map.of("trace_id", context.runId() + "-" + evalCase.caseId()),
                (handle, evalCase) -> new NormalizedTrace(
                        String.valueOf(((Map<?, ?>) handle).get("trace_id")),
                        "ok",
                        List.of(new TraceEvent("Agent", "answer")),
                        Map.of()),
                List.of(),
                List.of());
    }

    private static List<EvalCase> cases() {
        return List.of(
                new EvalCase("C1", Map.of("message", "one"), Map.of(), "default", Map.of(), "suite"),
                new EvalCase("C2", Map.of("message", "two"), Map.of(), "default", Map.of(), "suite"));
    }
}
