package io.assay.engine;

import io.assay.model.EvaluationBudget;
import io.assay.runner.RetryPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the persisted run metadata and the summary returned by one evaluation run. */
final class RunSummary {

    /** The five result counts of one finished run, grouped so call sites cannot transpose them. */
    record Counts(
            int caseCount,
            int requestedCaseCount,
            int skippedCaseCount,
            int hardFailures,
            int softWarnings) {
    }

    private RunSummary() {
    }

    static Map<String, Object> metadata(
            int caseCount,
            int workers,
            int requestedWorkers,
            int maxInFlight,
            int retries,
            double timeoutSeconds,
            boolean collectFewShot,
            String runIdentity) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("case_count", caseCount);
        metadata.put("workers", workers);
        metadata.put("requested_workers", requestedWorkers);
        metadata.put("max_in_flight", maxInFlight);
        metadata.put("retries", retries);
        metadata.put("timeout_seconds", timeoutSeconds);
        metadata.put("collect_few_shot", collectFewShot);
        metadata.put("run_identity", runIdentity);
        return metadata;
    }

    static Map<String, Object> summary(
            String runId,
            String suite,
            String source,
            String status,
            Counts counts,
            EvaluationBudget budget,
            BudgetLedger ledger,
            RetryPolicy retryPolicy,
            CircuitBreaker adapterBreaker,
            CircuitBreaker reviewerBreaker,
            List<Map<String, Object>> results) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", runId);
        summary.put("suite", suite);
        summary.put("source", source);
        summary.put("status", status);
        summary.put("case_count", counts.caseCount());
        summary.put("requested_case_count", counts.requestedCaseCount());
        summary.put("skipped_case_count", counts.skippedCaseCount());
        summary.put("hard_failures", counts.hardFailures());
        summary.put("soft_warnings", counts.softWarnings());
        if (budget.enabled()) {
            summary.put("budget", ledger.snapshot());
        }
        if (retryPolicy.enabled()) {
            Map<String, Object> retry = new LinkedHashMap<>();
            retry.put("base_delay_ms", retryPolicy.baseDelay().toMillis());
            retry.put("max_delay_ms", retryPolicy.maxDelay().toMillis());
            retry.put("jitter", retryPolicy.jitter());
            summary.put("retry_policy", retry);
        }
        if (adapterBreaker.enabled() || reviewerBreaker.enabled()) {
            Map<String, Object> breaker = new LinkedHashMap<>();
            breaker.put("adapter", adapterBreaker.snapshot());
            breaker.put("reviewer", reviewerBreaker.snapshot());
            summary.put("circuit_breaker", breaker);
        }
        summary.put("results", results);
        return summary;
    }
}
