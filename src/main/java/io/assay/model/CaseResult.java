package io.assay.model;

import io.assay.json.Jsonable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the framework learned about one case.
 *
 * <p>{@code hardPass} and {@code softWarningCount} are derived, not stored. {@link #toJson()} therefore
 * emits only the declared fields; the result store adds the two derived counters when it persists a
 * record.
 */
public final class CaseResult implements Jsonable {

    private final String runId;
    private final String suite;
    private final String source;
    private final EvalCase evalCase;
    private final NormalizedTrace trace;
    private final List<CheckResult> checks;
    private String error;
    private Map<String, Object> llmReview = Map.of();
    private Map<String, Object> fewShotCandidate = Map.of();

    public CaseResult(
            String runId,
            String suite,
            String source,
            EvalCase evalCase,
            NormalizedTrace trace,
            List<CheckResult> checks) {
        this(runId, suite, source, evalCase, trace, checks, null);
    }

    public CaseResult(
            String runId,
            String suite,
            String source,
            EvalCase evalCase,
            NormalizedTrace trace,
            List<CheckResult> checks,
            String error) {
        this.runId = runId;
        this.suite = suite;
        this.source = source;
        this.evalCase = evalCase;
        this.trace = trace;
        this.checks = new ArrayList<>(checks);
        this.error = error;
    }

    public String runId() {
        return runId;
    }

    public String suite() {
        return suite;
    }

    public String source() {
        return source;
    }

    public EvalCase evalCase() {
        return evalCase;
    }

    public NormalizedTrace trace() {
        return trace;
    }

    public List<CheckResult> checks() {
        return checks;
    }

    public String error() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Map<String, Object> llmReview() {
        return llmReview;
    }

    public void setLlmReview(Map<String, Object> llmReview) {
        this.llmReview = llmReview == null ? Map.of() : llmReview;
    }

    public Map<String, Object> fewShotCandidate() {
        return fewShotCandidate;
    }

    public void setFewShotCandidate(Map<String, Object> fewShotCandidate) {
        this.fewShotCandidate = fewShotCandidate == null ? Map.of() : fewShotCandidate;
    }

    public boolean hardPass() {
        if (error != null) {
            return false;
        }
        for (CheckResult check : checks) {
            if ("hard".equals(check.level()) && !check.passed()) {
                return false;
            }
        }
        return true;
    }

    public int softWarningCount() {
        int count = 0;
        for (CheckResult check : checks) {
            if (("soft".equals(check.level()) || "candidate".equals(check.level())) && !check.passed()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", runId);
        result.put("suite", suite);
        result.put("source", source);
        result.put("case", evalCase);
        result.put("trace", trace);
        List<Object> checkRows = new ArrayList<>(checks.size());
        for (CheckResult check : checks) {
            checkRows.add(check.toJson());
        }
        result.put("checks", checkRows);
        result.put("error", error);
        result.put("llm_review", llmReview);
        result.put("few_shot_candidate", fewShotCandidate);
        return result;
    }
}
