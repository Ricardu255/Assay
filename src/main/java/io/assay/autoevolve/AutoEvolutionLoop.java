package io.assay.autoevolve;

import io.assay.engine.EvaluationEngine;
import io.assay.evolve.EvolutionEngine;
import io.assay.json.Json;
import io.assay.model.AutoEvolutionAdapter;
import io.assay.model.EvalCase;
import io.assay.model.EvolutionBudget;
import io.assay.model.EvolutionCandidate;
import io.assay.model.EvolutionDiagnosis;
import io.assay.model.EvolutionPolicy;
import io.assay.model.ProjectAdapter;
import io.assay.model.RetryableEvolverException;
import io.assay.model.TextCandidate;
import io.assay.model.TextFileOperation;
import io.assay.store.Results;
import io.assay.support.ExclusiveFileLock;
import io.assay.support.Names;
import io.assay.workspace.TextArtifactWorkspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Diagnose, propose, stage, evaluate, and repeat — all inside a sandbox.
 *
 * <p>Every round writes a checkpoint before it starts work, so an interruption, a time budget, or a
 * provider outage can be resumed with the same arguments. A candidate that is accepted moves the
 * sandbox's "current" pointer; it never reaches the domain project.
 */
public final class AutoEvolutionLoop {

    private static final List<String> ROLES = List.of("improvement", "regression", "holdout");

    private final Results store;
    private final TextArtifactWorkspace workspace;
    private final EvolutionPolicy policy;
    private final int workers;
    private final int retries;
    private final double timeoutSeconds;

    public AutoEvolutionLoop(Results store, TextArtifactWorkspace workspace) {
        this(store, workspace, null, 1, 0, 30);
    }

    public AutoEvolutionLoop(
            Results store,
            TextArtifactWorkspace workspace,
            EvolutionPolicy policy,
            int workers,
            int retries,
            double timeoutSeconds) {
        this.store = store;
        this.workspace = workspace;
        this.policy = policy == null ? new EvolutionPolicy() : policy;
        this.workers = workers;
        this.retries = retries;
        this.timeoutSeconds = timeoutSeconds;
    }

    public Map<String, Object> run(
            AutoEvolutionAdapter adapter, Map<String, List<EvalCase>> datasets, String loopId, String source) {
        return run(adapter, datasets, new EvolutionBudget(), loopId, source, false);
    }

    public Map<String, Object> run(
            AutoEvolutionAdapter adapter,
            Map<String, List<EvalCase>> datasets,
            EvolutionBudget budget,
            String loopId,
            String source,
            boolean resume) {
        if (resume && loopId == null) {
            throw new IllegalArgumentException("resume requires loop_id");
        }
        String effectiveLoopId = loopId != null ? loopId : "auto-evolution-" + shortId();
        try (ExclusiveFileLock ignored = workspace.lockLoop(effectiveLoopId)) {
            return runLocked(adapter, datasets, budget, effectiveLoopId, source, resume);
        }
    }

    private ProjectAdapter buildAdapter(AutoEvolutionAdapter adapter, Path artifact, String version) {
        return adapter.buildAdapter().build(artifact, version);
    }

    private EvaluationEngine engine(ProjectAdapter adapter, Path artifact) {
        return new EvaluationEngine(
                adapter, store, null, workers, retries, timeoutSeconds, false, artifactSha256(artifact));
    }

    private static void validateDiagnosis(EvolutionDiagnosis diagnosis, AutoEvolutionAdapter adapter) {
        if (!Objects.equals(diagnosis.targetType(), adapter.targetType())
                || !Objects.equals(diagnosis.targetId(), adapter.targetId())) {
            throw new IllegalArgumentException("diagnosis target does not match auto evolution adapter");
        }
    }

    private static void validateCandidate(TextCandidate candidate, String baselineVersion) {
        Names.safeName(candidate.candidateId(), "candidate_id");
        Names.safeName(candidate.candidateVersion(), "candidate_version");
        if (Objects.equals(candidate.candidateVersion(), baselineVersion)) {
            throw new IllegalArgumentException("candidate version must differ from baseline version");
        }
        if (!candidate.files().isEmpty() && !candidate.operations().isEmpty()) {
            throw new IllegalArgumentException("candidate must use files or operations, not both");
        }
        if (candidate.content().trim().isEmpty()
                && candidate.files().isEmpty()
                && candidate.operations().isEmpty()) {
            throw new IllegalArgumentException("candidate content, files, or operations must not be empty");
        }
    }

    /** Describes the frozen dataset identity so a resume can prove nothing moved underneath it. */
    static Map<String, Object> datasetManifest(Map<String, List<EvalCase>> datasets) {
        List<String> identityFields =
                List.of(
                        "evaluated_target_id",
                        "evaluated_target_version",
                        "evaluator_skill_id",
                        "evaluator_skill_version");
        Map<String, Object> manifest = new LinkedHashMap<>();
        for (Map.Entry<String, List<EvalCase>> entry : new TreeMap<>(datasets).entrySet()) {
            List<EvalCase> cases = entry.getValue();
            List<Object> canonicalCases = new ArrayList<>(cases.size());
            for (EvalCase evalCase : cases) {
                canonicalCases.add(evalCase.toJson());
            }
            Map<String, Object> identity = new LinkedHashMap<>();
            for (String field : identityFields) {
                List<String> values = new ArrayList<>();
                for (EvalCase evalCase : cases) {
                    Object value = evalCase.metadata().get(field);
                    if (value != null) {
                        String text = String.valueOf(value);
                        if (!values.contains(text)) {
                            values.add(text);
                        }
                    }
                }
                if (!values.isEmpty()) {
                    values.sort(java.util.Comparator.naturalOrder());
                    identity.put(field, values);
                }
            }
            Map<String, Object> roleManifest = new LinkedHashMap<>();
            roleManifest.put("case_count", cases.size());
            roleManifest.put(
                    "sha256",
                    sha256Hex(Json.canonical(canonicalCases).getBytes(StandardCharsets.UTF_8)));
            roleManifest.putAll(identity);
            manifest.put(entry.getKey(), roleManifest);
        }
        return manifest;
    }

    /** Hashes an artifact so a resume can tell that the sandbox content changed. */
    static String artifactSha256(Path path) {
        return sha256Hex(Json.canonical(TextArtifactWorkspace.readArtifact(path)).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder builder = new StringBuilder();
            for (byte item : digest.digest(value)) {
                builder.append(Character.forDigit((item >> 4) & 0xF, 16));
                builder.append(Character.forDigit(item & 0xF, 16));
            }
            return builder.toString().toUpperCase(java.util.Locale.ROOT);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required but unavailable", error);
        }
    }

    private static final class TimeBudgetExhausted extends RuntimeException {
    }

    private static final class EvolverCallBudgetExhausted extends RuntimeException {
    }

    private Map<String, Object> runLocked(
            AutoEvolutionAdapter adapter,
            Map<String, List<EvalCase>> datasets,
            EvolutionBudget requestedBudget,
            String loopId,
            String source,
            boolean resume) {
        if (!"online".equals(source)) {
            throw new IllegalArgumentException("automatic evolution requires fresh online evaluation");
        }
        List<String> missing = new ArrayList<>();
        for (String role : ROLES) {
            if (!datasets.containsKey(role) || datasets.get(role).isEmpty()) {
                missing.add(role);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "automatic evolution requires non-empty improvement, regression, and holdout datasets");
        }
        if (!Files.exists(adapter.baselineArtifact())) {
            throw new IllegalArgumentException("baseline artifact does not exist: " + adapter.baselineArtifact());
        }

        EvolutionBudget budget = requestedBudget == null ? new EvolutionBudget() : requestedBudget;
        Map<String, Object> datasetManifest = datasetManifest(datasets);
        LoopUsage usage = new LoopUsage();
        long started = System.nanoTime();

        Path currentPath;
        String currentVersion;
        List<Object> rounds;
        Map<String, Object> pendingRound;

        if (resume) {
            Map<String, Object> checkpoint = workspace.loadCheckpoint(loopId);
            if (!Objects.equals(checkpoint.get("target_type"), adapter.targetType())
                    || !Objects.equals(checkpoint.get("target_id"), adapter.targetId())
                    || !Objects.equals(checkpoint.get("initial_version"), adapter.baselineVersion())) {
                throw new IllegalArgumentException("checkpoint target does not match auto evolution adapter");
            }
            Object storedDatasets = checkpoint.get("datasets");
            if (storedDatasets != null
                    && !Json.normalize(storedDatasets).equals(Json.normalize(datasetManifest))) {
                throw new IllegalArgumentException("checkpoint datasets do not match the current frozen datasets");
            }
            if (!Json.normalize(checkpoint.get("policy")).equals(Json.normalize(policy.toJson()))) {
                throw new IllegalArgumentException("checkpoint policy does not match the current frozen policy");
            }
            currentPath = Path.of(String.valueOf(checkpoint.get("current_artifact")));
            if (!Files.exists(currentPath)) {
                throw new IllegalArgumentException("checkpoint artifact does not exist: " + currentPath);
            }
            if (!Objects.equals(checkpoint.get("current_artifact_sha256"), artifactSha256(currentPath))) {
                throw new IllegalArgumentException("checkpoint artifact content changed");
            }
            if ("completed".equals(checkpoint.get("status"))) {
                return checkpoint;
            }
            currentVersion = String.valueOf(checkpoint.get("current_version"));
            rounds = new ArrayList<>(asList(checkpoint.get("rounds")));
            Object rawPending = checkpoint.get("pending_round");
            pendingRound = rawPending instanceof Map ? asMap(rawPending) : null;
            Map<String, Object> storedUsage = asMap(checkpoint.get("usage"));
            usage.elapsedBefore = doubleValue(storedUsage.get("elapsed_seconds"));
            usage.evolverCalls = intValue(storedUsage.get("evolver_calls"));
            usage.evolverRetries = intValue(storedUsage.get("evolver_retries"));
        } else {
            currentPath = workspace.snapshot(loopId, adapter.baselineArtifact());
            currentVersion = adapter.baselineVersion();
            rounds = new ArrayList<>();
            pendingRound = null;
        }

        LoopState state =
                new LoopState(
                        loopId,
                        adapter,
                        datasetManifest,
                        budget,
                        usage,
                        started,
                        currentPath,
                        currentVersion,
                        rounds,
                        pendingRound);

        try {
            state.phase = "initialization";
            state.save();

            for (int roundNumber = rounds.size() + 1; roundNumber <= budget.maxRounds(); roundNumber++) {
                if (state.timeExhausted()) {
                    return state.save("time_budget_exhausted");
                }
                if (!rounds.isEmpty() && state.pendingRound == null && state.evolverExhausted()) {
                    return state.save("evolver_call_budget_exhausted");
                }

                state.phase = "baseline_evaluation";
                Path evaluationPath = state.pendingRound != null
                        ? Path.of(String.valueOf(state.pendingRound.get("baseline_artifact")))
                        : state.currentPath;
                String evaluationVersion = state.pendingRound != null
                        ? String.valueOf(state.pendingRound.get("baseline_version"))
                        : state.currentVersion;
                EvaluationEngine baselineEngine =
                        engine(buildAdapter(adapter, evaluationPath, evaluationVersion), evaluationPath);

                Map<String, Object> diagnosisRun;
                EvolutionDiagnosis diagnosis;
                List<TextCandidate> proposals;

                if (state.pendingRound == null) {
                    diagnosisRun =
                            baselineEngine.runSuite(
                                    datasets.get("improvement"),
                                    "improvement",
                                    "online",
                                    loopId + "-round-" + roundNumber + "-diagnosis",
                                    resume);
                    if (intValue(diagnosisRun.get("hard_failures")) == 0
                            && intValue(diagnosisRun.get("soft_warnings")) == 0) {
                        return state.save("completed");
                    }
                    if (state.timeExhausted()) {
                        return state.save("time_budget_exhausted");
                    }
                    if (state.evolverExhausted()) {
                        return state.save("evolver_call_budget_exhausted");
                    }

                    state.phase = "diagnosis";
                    Map<String, Object> diagnosisEvidence = diagnosisRun;
                    diagnosis =
                            (EvolutionDiagnosis)
                                    state.invokeEvolver(
                                            () -> adapter.diagnose().diagnose(diagnosisEvidence));
                    validateDiagnosis(diagnosis, adapter);
                    if (state.timeExhausted()) {
                        return state.save("time_budget_exhausted");
                    }
                    if (state.evolverExhausted()) {
                        return state.save("evolver_call_budget_exhausted");
                    }

                    state.phase = "candidate_generation";
                    EvolutionDiagnosis diagnosisForGeneration = diagnosis;
                    Path artifactForGeneration = evaluationPath;
                    int roundForGeneration = roundNumber;
                    List<TextCandidate> generated =
                            new ArrayList<>(
                                    asCandidates(
                                            state.invokeEvolver(
                                                    () ->
                                                            adapter.generateCandidates()
                                                                    .generate(
                                                                            diagnosisForGeneration,
                                                                            TextArtifactWorkspace.readArtifact(
                                                                                    artifactForGeneration),
                                                                            roundForGeneration))));
                    proposals = generated.size() > budget.maxCandidatesPerRound()
                            ? generated.subList(0, budget.maxCandidatesPerRound())
                            : generated;
                    if (proposals.isEmpty()) {
                        Map<String, Object> emptyRound = new LinkedHashMap<>();
                        emptyRound.put("round", roundNumber);
                        emptyRound.put("diagnosis", diagnosis.toJson());
                        emptyRound.put("diagnosis_run", diagnosisRun);
                        emptyRound.put("candidates", new ArrayList<>());
                        rounds.add(emptyRound);
                        return state.save("no_candidates");
                    }
                    Map<String, Object> pending = new LinkedHashMap<>();
                    pending.put("round", roundNumber);
                    pending.put("baseline_artifact", evaluationPath.toString());
                    pending.put("baseline_version", evaluationVersion);
                    pending.put("diagnosis", diagnosis.toJson());
                    pending.put("diagnosis_run", diagnosisRun);
                    List<Object> proposalRows = new ArrayList<>(proposals.size());
                    for (TextCandidate proposal : proposals) {
                        proposalRows.add(proposal.toJson());
                    }
                    pending.put("proposals", proposalRows);
                    pending.put("candidates", new ArrayList<>());
                    state.pendingRound = pending;
                    state.save();
                } else {
                    if (intValue(state.pendingRound.get("round")) != roundNumber) {
                        throw new IllegalArgumentException("checkpoint pending round does not match the next round");
                    }
                    diagnosisRun = asMap(state.pendingRound.get("diagnosis_run"));
                    diagnosis = EvolutionDiagnosis.fromJson(asMap(state.pendingRound.get("diagnosis")));
                    proposals = new ArrayList<>();
                    for (Object rawProposal : asList(state.pendingRound.get("proposals"))) {
                        Map<String, Object> value = asMap(rawProposal);
                        proposals.add(
                                new TextCandidate(
                                        String.valueOf(value.getOrDefault("candidate_id", "")),
                                        String.valueOf(value.getOrDefault("candidate_version", "")),
                                        String.valueOf(value.getOrDefault("content", "")),
                                        String.valueOf(value.getOrDefault("summary", "")),
                                        String.valueOf(value.getOrDefault("change_type", "skill")),
                                        asMap(value.get("metadata")),
                                        stringMap(value.get("files")),
                                        TextFileOperation.listFromJson(value.get("operations"))));
                    }
                }

                @SuppressWarnings("unchecked")
                List<Object> candidateResults =
                        (List<Object>) state.pendingRound.computeIfAbsent("candidates", key -> new ArrayList<>());
                boolean accepted =
                        !candidateResults.isEmpty()
                                && "accept".equals(decisionOf(candidateResults.get(candidateResults.size() - 1)));

                List<TextCandidate> remaining =
                        accepted ? List.of() : proposals.subList(Math.min(candidateResults.size(), proposals.size()), proposals.size());
                for (TextCandidate candidate : remaining) {
                    if (state.timeExhausted()) {
                        return state.save("time_budget_exhausted");
                    }
                    validateCandidate(candidate, evaluationVersion);
                    Path candidatePath =
                            workspace.stage(
                                    loopId,
                                    roundNumber,
                                    candidate,
                                    adapter.baselineArtifact().getFileName().toString(),
                                    evaluationPath);
                    EvolutionCandidate change =
                            new EvolutionCandidate(
                                    candidate.candidateId(),
                                    adapter.targetType(),
                                    adapter.targetId(),
                                    evaluationVersion,
                                    candidate.candidateVersion(),
                                    candidate.changeType(),
                                    candidatePath.toString(),
                                    candidate.summary(),
                                    candidate.metadata());
                    state.phase = "candidate_evaluation";
                    Map<String, Object> result =
                            new EvolutionEngine(
                                            baselineEngine,
                                            engine(
                                                    buildAdapter(adapter, candidatePath, candidate.candidateVersion()),
                                                    candidatePath),
                                            policy)
                                    .run(
                                            change,
                                            datasets,
                                            "online",
                                            loopId + "-round-" + roundNumber + "-" + candidate.candidateId(),
                                            resume);
                    Map<String, Object> candidateResult = new LinkedHashMap<>();
                    candidateResult.put("candidate", candidate.toJson());
                    candidateResult.put("artifact_path", candidatePath.toString());
                    candidateResult.put("evaluation", result);
                    candidateResults.add(candidateResult);
                    if ("accept".equals(result.get("decision"))) {
                        state.currentPath = candidatePath;
                        state.currentVersion = candidate.candidateVersion();
                        accepted = true;
                    }
                    state.save();
                    if (accepted) {
                        break;
                    }
                }

                Map<String, Object> roundResult = new LinkedHashMap<>();
                roundResult.put("round", roundNumber);
                roundResult.put("diagnosis", diagnosis.toJson());
                roundResult.put("diagnosis_run", diagnosisRun);
                roundResult.put("candidates", candidateResults);
                rounds.add(roundResult);
                state.pendingRound = null;
                state.save();
                if (!accepted) {
                    return state.save("no_acceptable_candidate");
                }

                Map<String, Object> lastCandidateResult = asMap(candidateResults.get(candidateResults.size() - 1));
                Map<String, Object> acceptedImprovement =
                        asMap(asMap(asMap(lastCandidateResult.get("evaluation")).get("candidate_runs"))
                                .get("improvement"));
                if (intValue(acceptedImprovement.get("hard_failures")) == 0
                        && intValue(acceptedImprovement.get("soft_warnings")) == 0) {
                    return state.save("completed");
                }
            }
            return state.save("budget_exhausted");
        } catch (TimeBudgetExhausted error) {
            return state.save("time_budget_exhausted");
        } catch (EvolverCallBudgetExhausted error) {
            return state.save("evolver_call_budget_exhausted");
        } catch (RuntimeException error) {
            return state.save("failed", String.valueOf(error));
        }
    }

    private static String decisionOf(Object rawCandidateResult) {
        return String.valueOf(asMap(asMap(rawCandidateResult).get("evaluation")).get("decision"));
    }

    /** Mutable bookkeeping shared by the loop body and its budget checks. */
    private static final class LoopUsage {
        private double elapsedBefore;
        private int evolverCalls;
        private int evolverRetries;
    }

    /** The loop's live state, including everything a checkpoint needs to describe. */
    private final class LoopState {

        private final String loopId;
        private final AutoEvolutionAdapter adapter;
        private final Map<String, Object> datasetManifest;
        private final EvolutionBudget budget;
        private final LoopUsage usage;
        private final long started;
        private Path currentPath;
        private String currentVersion;
        private final List<Object> rounds;
        private Map<String, Object> pendingRound;
        private String phase = "initialization";

        private LoopState(
                String loopId,
                AutoEvolutionAdapter adapter,
                Map<String, Object> datasetManifest,
                EvolutionBudget budget,
                LoopUsage usage,
                long started,
                Path currentPath,
                String currentVersion,
                List<Object> rounds,
                Map<String, Object> pendingRound) {
            this.loopId = loopId;
            this.adapter = adapter;
            this.datasetManifest = datasetManifest;
            this.budget = budget;
            this.usage = usage;
            this.started = started;
            this.currentPath = currentPath;
            this.currentVersion = currentVersion;
            this.rounds = rounds;
            this.pendingRound = pendingRound;
        }

        private double elapsedSeconds() {
            return usage.elapsedBefore + (System.nanoTime() - started) / 1_000_000_000.0;
        }

        private boolean timeExhausted() {
            return budget.maxElapsedSeconds() != null && elapsedSeconds() >= budget.maxElapsedSeconds();
        }

        private boolean evolverExhausted() {
            return budget.maxEvolverCalls() != null && usage.evolverCalls >= budget.maxEvolverCalls();
        }

        /**
         * Runs one evolver call against the budget, retrying once when the provider returned something
         * unusable. Both the attempt and the retry are counted, because both cost a provider call.
         */
        private Object invokeEvolver(Supplier<Object> action) {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (timeExhausted()) {
                    throw new TimeBudgetExhausted();
                }
                if (evolverExhausted()) {
                    throw new EvolverCallBudgetExhausted();
                }
                usage.evolverCalls++;
                try {
                    return action.get();
                } catch (RetryableEvolverException error) {
                    if (attempt == 1) {
                        throw error;
                    }
                    usage.evolverRetries++;
                }
            }
            throw new IllegalStateException("unreachable");
        }

        private Map<String, Object> state(String status, String error) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("loop_id", loopId);
            result.put("status", status);
            result.put("target_type", adapter.targetType());
            result.put("target_id", adapter.targetId());
            result.put("initial_version", adapter.baselineVersion());
            result.put("current_version", currentVersion);
            result.put("current_artifact", currentPath.toString());
            result.put("current_artifact_sha256", artifactSha256(currentPath));
            result.put("datasets", datasetManifest);
            result.put("policy", policy.toJson());
            result.put("budget", budget.toJson());
            Map<String, Object> usageRow = new LinkedHashMap<>();
            usageRow.put("elapsed_seconds", round3(elapsedSeconds()));
            usageRow.put("evolver_calls", usage.evolverCalls);
            usageRow.put("evolver_retries", usage.evolverRetries);
            result.put("usage", usageRow);
            result.put("rounds", rounds);
            if (error != null) {
                result.put("failed_phase", phase);
                result.put("error", error);
            }
            if (pendingRound != null) {
                result.put("pending_round", pendingRound);
            }
            return result;
        }

        private Map<String, Object> save() {
            return save("running", null);
        }

        private Map<String, Object> save(String status) {
            return save(status, null);
        }

        private Map<String, Object> save(String status, String error) {
            Map<String, Object> snapshot = state(status, error);
            workspace.saveCheckpoint(loopId, snapshot);
            return snapshot;
        }
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : asMap(value).entrySet()) {
            result.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<TextCandidate> asCandidates(Object value) {
        return value instanceof List ? (List<TextCandidate>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
