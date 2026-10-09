package io.assay.examples;

import io.assay.engine.ProjectAdapterFactory;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.Rule;
import io.assay.model.RunContext;
import io.assay.model.TraceEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two versions of the intent router used by the {@code evolve} walkthrough.
 *
 * <p>Version 1 routes everything to GENERAL; version 2 recognises support requests. Running
 * {@code evolve} against both is what the README's example does, and it exercises every decision the
 * framework can reach.
 */
public final class IntentRouterAdapters {

    static final List<String> SUPPORT_TERMS = List.of("support", "password", "billing");

    private IntentRouterAdapters() {
    }

    /** The baseline release. */
    public static final class Baseline implements ProjectAdapterFactory {

        @Override
        public ProjectAdapter create() {
            return build("1", false);
        }
    }

    /** The candidate release under evaluation. */
    public static final class Candidate implements ProjectAdapterFactory {

        @Override
        public ProjectAdapter create() {
            return build("2", true);
        }
    }

    static ProjectAdapter build(String version, boolean supportRouting) {
        return new ProjectAdapter(
                "intent-router-" + version,
                (evalCase, context) -> callAgent(evalCase, context, supportRouting),
                (handle, evalCase) -> readTrace(handle, version),
                List.of(Rule.of("route", "fields.route", "route")),
                List.of());
    }

    private static Object callAgent(EvalCase evalCase, RunContext context, boolean supportRouting) {
        String message = Shared.text(evalCase.payload(), "message").toLowerCase();
        boolean isSupport = SUPPORT_TERMS.stream().anyMatch(message::contains);
        String route = supportRouting && isSupport ? "SUPPORT" : "GENERAL";
        Map<String, Object> expected = Shared.asMap(evalCase.expected());
        double quality = route.equals(String.valueOf(expected.get("route"))) ? 0.9 : 0.2;

        Map<String, Object> handle = new LinkedHashMap<>();
        handle.put("trace_id", context.runId() + "-" + evalCase.caseId());
        handle.put("output", Map.of("route", route));
        handle.put("route", route);
        handle.put("quality", quality);
        return handle;
    }

    private static NormalizedTrace readTrace(Object handle, String version) {
        Map<String, Object> values = Shared.asMap(handle);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("route", values.get("route"));
        fields.put("quality", values.get("quality"));
        return new NormalizedTrace(
                String.valueOf(values.get("trace_id")),
                values.get("output"),
                List.of(new TraceEvent("IntentRouter", "route", 2)),
                fields,
                Map.of(),
                "skill",
                "intent-router",
                version,
                Map.of());
    }
}
