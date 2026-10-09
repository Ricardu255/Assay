package io.assay.model;

import java.util.List;
import io.assay.support.Immutable;

/**
 * The four capabilities a domain project supplies.
 *
 * <p>A domain adapter is an ordinary class — or a bundle of lambdas — compiled once and referenced by
 * class name or by a {@code ServiceLoader} registration. {@link io.assay.engine.AdapterLoader}
 * resolves that reference.
 */
public record ProjectAdapter(
        String name,
        AgentCaller callAgent,
        TraceReader readTrace,
        List<Rule> hardGates,
        List<Rule> softQuality,
        Integer maxConcurrency) {

    public ProjectAdapter {
        hardGates = hardGates == null ? List.of() : Immutable.list(hardGates);
        softQuality = softQuality == null ? List.of() : Immutable.list(softQuality);
        if (maxConcurrency != null && maxConcurrency < 1) {
            throw new IllegalArgumentException("max_concurrency must be positive");
        }
    }

    public ProjectAdapter(
            String name, AgentCaller callAgent, TraceReader readTrace, List<Rule> hardGates, List<Rule> softQuality) {
        this(name, callAgent, readTrace, hardGates, softQuality, null);
    }

    /** Invokes the target and returns the domain's native handle. */
    @FunctionalInterface
    public interface AgentCaller {
        Object call(EvalCase evalCase, RunContext context);
    }

    /** Converts a native handle into the framework's stable trace contract. */
    @FunctionalInterface
    public interface TraceReader {
        NormalizedTrace read(Object handle, EvalCase evalCase);
    }
}
