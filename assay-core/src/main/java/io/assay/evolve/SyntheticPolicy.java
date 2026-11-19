package io.assay.evolve;

import io.assay.model.EvalCase;
import io.assay.model.EvolutionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Enforces where generated cases may appear and how much of the improvement set they may occupy.
 *
 * <p>Regression and holdout are the evidence a version comparison trusts; a synthetic case there
 * would let a candidate be judged on data it was tuned against. Those two roles reject generated
 * cases outright. Improvement may use them, but only within the configured ratio and with provenance
 * when the policy requires it.
 */
final class SyntheticPolicy {

    private SyntheticPolicy() {
    }

    static List<String> validate(Map<String, List<EvalCase>> datasets, EvolutionPolicy policy) {
        List<String> problems = new ArrayList<>();
        for (String role : EvolutionSupport.ROLES) {
            List<EvalCase> cases = datasets.get(role);
            if (cases == null) {
                continue;
            }
            int synthetic = 0;
            int missingProvenance = 0;
            for (EvalCase evalCase : cases) {
                if (!isGenerated(evalCase)) {
                    continue;
                }
                synthetic++;
                if (policy.requireProvenance() && !hasProvenance(evalCase)) {
                    missingProvenance++;
                }
            }
            if (synthetic > 0 && !"improvement".equals(role)) {
                problems.add(role + ": " + synthetic
                        + " synthetic cases are only allowed in the improvement set");
            }
            if (missingProvenance > 0) {
                problems.add(role + ": " + missingProvenance
                        + " synthetic cases lack source_file or origin_case_id provenance");
            }
            if ("improvement".equals(role) && policy.syntheticRatioCap() != null && !cases.isEmpty()) {
                double ratio = (double) synthetic / cases.size();
                if (ratio > policy.syntheticRatioCap() + 1e-9) {
                    problems.add("improvement: synthetic ratio "
                            + String.format(Locale.ROOT, "%.3f", ratio)
                            + " exceeds the cap " + policy.syntheticRatioCap());
                }
            }
        }
        return problems;
    }

    private static boolean isGenerated(EvalCase evalCase) {
        String source = text(evalCase.metadata().get("source")).toLowerCase(Locale.ROOT);
        return "synthetic".equals(source) || "adversarial".equals(source);
    }

    private static boolean hasProvenance(EvalCase evalCase) {
        return !text(evalCase.metadata().get("source_file")).isEmpty()
                || !text(evalCase.metadata().get("origin_case_id")).isEmpty();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
