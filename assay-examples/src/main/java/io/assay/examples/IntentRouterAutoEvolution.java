package io.assay.examples;

import io.assay.engine.AutoEvolutionAdapterFactory;
import io.assay.model.AutoEvolutionAdapter;
import io.assay.model.EvolutionDiagnosis;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.Rule;
import io.assay.model.TextCandidate;
import io.assay.model.TraceEvent;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A deterministic {@code evolve-auto} fixture with no model in the loop.
 *
 * <p>It proposes two candidates in its first round: one that replaces the baseline keyword list and
 * breaks the holdout set, and one that extends it. The framework must roll the first back and accept the
 * second — which is the property the automatic-evolution tests assert.
 */
public final class IntentRouterAutoEvolution implements AutoEvolutionAdapterFactory {

    /** Directory holding the example data files; override when running from elsewhere. */
    private static final String EXAMPLES_DIR_PROPERTY = "assay.examples.dir";

    @Override
    public AutoEvolutionAdapter create() {
        return new AutoEvolutionAdapter(
                "intent-router-auto-evolution",
                baselineArtifact(),
                "1",
                "skill",
                "intent-router",
                IntentRouterAutoEvolution::diagnose,
                IntentRouterAutoEvolution::generateCandidates,
                IntentRouterAutoEvolution::buildAdapter);
    }

    private static Path baselineArtifact() {
        String directory = System.getProperty(EXAMPLES_DIR_PROPERTY, "examples");
        return Path.of(directory, "auto_evolution.skill.txt");
    }

    /** Reads the keyword list out of a {@code support_keywords=a,b} artifact. */
    private static Set<String> keywords(Path artifact) {
        String content = AtomicFiles.readText(artifact);
        int equals = content.indexOf('=');
        String tail = equals < 0 ? content : content.substring(equals + 1);
        Set<String> keywords = new LinkedHashSet<>();
        for (String value : tail.split(",", -1)) {
            String trimmed = value.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                keywords.add(trimmed);
            }
        }
        return keywords;
    }

    static ProjectAdapter buildAdapter(Path artifact, String version) {
        Set<String> keywords = keywords(artifact);
        return new ProjectAdapter(
                "intent-router-" + version,
                (evalCase, context) -> {
                    String message = Shared.text(evalCase.payload(), "message").toLowerCase();
                    String route = keywords.stream().anyMatch(message::contains) ? "SUPPORT" : "GENERAL";
                    Map<String, Object> expected = Shared.asMap(evalCase.expected());
                    double quality = route.equals(String.valueOf(expected.get("route"))) ? 0.9 : 0.2;
                    Map<String, Object> handle = new LinkedHashMap<>();
                    handle.put("trace_id", context.runId() + "-" + evalCase.caseId());
                    handle.put("route", route);
                    handle.put("quality", quality);
                    return handle;
                },
                (handle, evalCase) -> {
                    Map<String, Object> values = Shared.asMap(handle);
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("route", values.get("route"));
                    fields.put("quality", values.get("quality"));
                    return new NormalizedTrace(
                            String.valueOf(values.get("trace_id")),
                            Map.of("route", values.get("route")),
                            List.of(new TraceEvent("IntentRouter", "route", 2)),
                            fields,
                            Map.of(),
                            "skill",
                            "intent-router",
                            version,
                            Map.of());
                },
                List.of(Rule.of("route", "fields.route", "route")),
                List.of());
    }

    private static EvolutionDiagnosis diagnose(Map<String, Object> summary) {
        List<String> failedIds = new ArrayList<>();
        if (summary.get("results") instanceof List<?> results) {
            for (Object rawResult : results) {
                Map<String, Object> result = Shared.asMap(rawResult);
                if (!Boolean.TRUE.equals(result.get("hard_pass"))) {
                    failedIds.add(String.valueOf(Shared.asMap(result.get("case")).get("case_id")));
                }
            }
        }
        return new EvolutionDiagnosis(
                "The support routing skill misses password-reset requests.",
                "skill",
                "intent-router",
                failedIds,
                List.of("IntentRouter"),
                List.of("Preserve existing support keywords."),
                Map.of());
    }

    private static List<TextCandidate> generateCandidates(
            EvolutionDiagnosis diagnosis, Object currentContent, int roundNumber) {
        if (roundNumber > 1) {
            return List.of();
        }
        String current = String.valueOf(currentContent);
        return List.of(
                new TextCandidate(
                        "replace-keywords",
                        "2-bad",
                        "support_keywords=password\n",
                        "Replace the old keyword with the failed-case keyword."),
                new TextCandidate(
                        "preserve-and-extend",
                        "2",
                        current.stripTrailing() + ",password\n",
                        "Preserve the baseline behavior and add password routing."));
    }
}
