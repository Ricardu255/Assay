package io.assay.store;

import io.assay.model.CaseResult;
import io.assay.model.CheckResult;
import java.util.List;
import java.util.Map;

/**
 * The persistence surface the evaluation, evolution, and CLI layers depend on.
 *
 * <p>Everything downstream only needs these methods, so a different backend — a JDBC store, an object
 * store, an in-memory double used by tests — can be dropped in without touching the engine. {@link
 * ResultStore} is the default implementation.
 */
public interface Results extends AutoCloseable {

    /** Holds the run's cross-process lock for the duration of a suite execution. */
    ResultStore.Handle lockRun(String runId);

    void startRun(String runId, String adapter, String suite, String source, Map<String, Object> metadata);

    void startRun(
            String runId,
            String adapter,
            String suite,
            String source,
            Map<String, Object> metadata,
            boolean resume,
            Map<String, String> caseManifest);

    void finishRun(String runId, String status);

    boolean hasCase(String runId, String caseId);

    void saveCase(CaseResult result);

    void saveCases(List<CaseResult> results);

    List<Map<String, Object>> listResults(String runId);

    void addCaseCheck(String runId, String caseId, CheckResult check);

    void saveReview(String runId, String caseId, String decision, String finalConclusion);

    List<Map<String, Object>> listReviewedResults(String decision);

    void saveEvolution(String experimentId, String candidateId, String decision, Map<String, Object> result);

    Map<String, Object> getEvolution(String experimentId);

    @Override
    void close();
}
