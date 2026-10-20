package io.assay.tracing.otel;

import io.assay.engine.EvaluationListener;
import io.assay.model.CaseResult;
import io.assay.model.EvalCase;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Emits one OpenTelemetry span per run, case, and batch.
 *
 * <p>Case spans are parented to their run span, so a trace shows the run and all its cases together.
 * The listener stores only local maps/ThreadLocals; the application owns the SDK, exporter, and
 * sampling. The core module has no OpenTelemetry dependency.
 */
public final class OtelEvaluationListener implements EvaluationListener {

    private static final AttributeKey<String> RUN_ID = AttributeKey.stringKey("assay.run_id");
    private static final AttributeKey<String> CASE_ID = AttributeKey.stringKey("assay.case_id");
    private static final AttributeKey<String> SUITE = AttributeKey.stringKey("assay.suite");
    private static final AttributeKey<String> SOURCE = AttributeKey.stringKey("assay.source");
    private static final AttributeKey<String> STATUS = AttributeKey.stringKey("assay.status");
    private static final AttributeKey<String> STAGE = AttributeKey.stringKey("assay.stage");
    private static final AttributeKey<String> ERROR = AttributeKey.stringKey("assay.error");
    private static final AttributeKey<Boolean> HARD_PASS = AttributeKey.booleanKey("assay.hard_pass");
    private static final AttributeKey<Long> SOFT_WARNINGS = AttributeKey.longKey("assay.soft_warnings");
    private static final AttributeKey<Long> CASE_COUNT = AttributeKey.longKey("assay.case_count");
    private static final AttributeKey<Long> BATCH_SIZE = AttributeKey.longKey("assay.batch.size");
    private static final AttributeKey<Long> DURATION_MS = AttributeKey.longKey("assay.duration_ms");

    private final Tracer tracer;
    private final Map<String, Span> runSpans = new ConcurrentHashMap<>();
    private final Map<String, Context> runContexts = new ConcurrentHashMap<>();
    private final ThreadLocal<Span> caseSpans = new ThreadLocal<>();
    private final ThreadLocal<Deque<Span>> stageSpans = ThreadLocal.withInitial(ArrayDeque::new);

    public OtelEvaluationListener(Tracer tracer) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    @Override
    public void onRunStarted(String runId, String suite, String source, int caseCount) {
        Span span = tracer.spanBuilder("assay.run")
                .setAttribute(RUN_ID, runId)
                .setAttribute(SUITE, tag(suite))
                .setAttribute(SOURCE, tag(source))
                .setAttribute(CASE_COUNT, (long) caseCount)
                .startSpan();
        runSpans.put(runId, span);
        runContexts.put(runId, Context.current().with(span));
    }

    @Override
    public void onCaseStarted(String runId, EvalCase evalCase) {
        var builder = tracer.spanBuilder("assay.case")
                .setAttribute(CASE_ID, evalCase.caseId())
                .setAttribute(SUITE, tag(evalCase.suite()));
        Context parent = runContexts.get(runId);
        if (parent != null) {
            builder.setParent(parent);
        }
        caseSpans.set(builder.startSpan());
    }

    @Override
    public void onCaseFinished(CaseResult result) {
        closeStages();
        Span span = caseSpans.get();
        caseSpans.remove();
        if (span == null) {
            return;
        }
        span.setAttribute(HARD_PASS, result.hardPass());
        span.setAttribute(SOFT_WARNINGS, (long) result.softWarningCount());
        span.setAttribute(STATUS, result.hardPass() ? "passed" : "failed");
        span.setStatus(result.hardPass() ? StatusCode.OK : StatusCode.ERROR);
        span.end();
    }

    @Override
    public void onStageStarted(String runId, Stage stage, EvalCase evalCase) {
        Span parent = caseSpans.get();
        if (parent == null) {
            parent = runSpans.get(runId);
        }
        var builder = tracer.spanBuilder("assay." + stage.name().toLowerCase(Locale.ROOT))
                .setAttribute(STAGE, stage.name().toLowerCase(Locale.ROOT))
                .setAttribute(RUN_ID, runId);
        if (evalCase != null) {
            builder.setAttribute(CASE_ID, evalCase.caseId());
            builder.setAttribute(SUITE, tag(evalCase.suite()));
        }
        if (parent != null) {
            builder.setParent(Context.current().with(parent));
        }
        stageSpans.get().push(builder.startSpan());
    }

    @Override
    public void onStageFinished(
            String runId,
            Stage stage,
            EvalCase evalCase,
            long durationNanos,
            RuntimeException error,
            Map<String, Object> attributes) {
        Deque<Span> stack = stageSpans.get();
        Span span = stack.isEmpty() ? null : stack.pop();
        if (stack.isEmpty()) {
            stageSpans.remove();
        }
        if (span == null) {
            return;
        }
        applyAttributes(span, attributes);
        span.setAttribute(DURATION_MS, durationNanos / 1_000_000);
        if (error == null) {
            span.setStatus(StatusCode.OK);
        } else {
            span.setAttribute(STATUS, "failed");
            span.setAttribute(ERROR, String.valueOf(error.getMessage()));
            span.setStatus(StatusCode.ERROR, String.valueOf(error.getMessage()));
            span.recordException(error);
        }
        span.end();
    }

    @Override
    public void onBatchSaved(int count, long durationNanos) {
        Span span = tracer.spanBuilder("assay.batch")
                .setAttribute(BATCH_SIZE, (long) count)
                .setAttribute(DURATION_MS, durationNanos / 1_000_000)
                .startSpan();
        span.setStatus(StatusCode.OK);
        span.end();
    }

    @Override
    public void onRunFinished(Map<String, Object> summary, long durationNanos) {
        String runId = String.valueOf(summary.getOrDefault("run_id", ""));
        closeStages();
        Span span = runSpans.remove(runId);
        runContexts.remove(runId);
        if (span == null) {
            return;
        }
        String status = String.valueOf(summary.getOrDefault("status", "unknown"));
        span.setAttribute(STATUS, status);
        span.setAttribute(CASE_COUNT, longValue(summary.get("case_count")));
        span.setAttribute(DURATION_MS, durationNanos / 1_000_000);
        span.setStatus("passed".equals(status) ? StatusCode.OK : StatusCode.ERROR);
        span.end();
    }

    @Override
    public void onRunFailed(String runId, RuntimeException error, long durationNanos) {
        closeStages();
        Span span = runSpans.remove(runId);
        runContexts.remove(runId);
        if (span == null) {
            return;
        }
        span.setAttribute(STATUS, "failed");
        span.setAttribute(ERROR, String.valueOf(error.getMessage()));
        span.setAttribute(DURATION_MS, durationNanos / 1_000_000);
        span.setStatus(StatusCode.ERROR, String.valueOf(error.getMessage()));
        span.recordException(error);
        span.end();
    }

    private void closeStages() {
        Deque<Span> stack = stageSpans.get();
        while (!stack.isEmpty()) {
            stack.pop().end();
        }
        stageSpans.remove();
    }

    private static void applyAttributes(Span span, Map<String, Object> attributes) {
        if (attributes == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Number number) {
                span.setAttribute(AttributeKey.longKey(entry.getKey()), number.longValue());
            } else if (value instanceof Boolean flag) {
                span.setAttribute(AttributeKey.booleanKey(entry.getKey()), flag);
            } else if (value != null) {
                span.setAttribute(AttributeKey.stringKey(entry.getKey()), String.valueOf(value));
            }
        }
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String tag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 64 ? trimmed : trimmed.substring(0, 64);
    }
}
