package io.assay.metrics;

import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.support.Immutable;
import java.util.List;

/**
 * Everything a metric may inspect for one case.
 *
 * <p>{@code trace} is the primary trace; {@code traces} carries the additional runs collected for
 * consistency checks, so a metric can compare reruns instead of only reading one trace.
 */
public record MetricContext(EvalCase evalCase, NormalizedTrace trace, List<NormalizedTrace> traces) {

    public MetricContext {
        if (traces == null || traces.isEmpty()) {
            traces = trace == null ? List.of() : List.of(trace);
        } else {
            traces = Immutable.list(traces);
        }
        if (trace == null && !traces.isEmpty()) {
            trace = traces.get(0);
        }
    }

    public MetricContext(EvalCase evalCase, NormalizedTrace trace) {
        this(evalCase, trace, trace == null ? List.of() : List.of(trace));
    }
}
