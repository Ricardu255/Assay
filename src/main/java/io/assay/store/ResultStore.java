package io.assay.store;

import io.assay.json.Json;
import io.assay.model.CaseResult;
import io.assay.model.CheckResult;
import io.assay.support.AtomicFiles;
import io.assay.support.ExclusiveFileLock;
import io.assay.support.Names;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Durable, resumable storage for runs, human reviews, and evolution experiments.
 *
 * <p>Records are persisted as JSON: one directory per run holding its metadata, its case results, and
 * the case-identity manifest. Case results are appended as JSONL and read back last-write-wins, which
 * gives upsert semantics while leaving a crash-truncated tail harmless; {@link #finishRun} compacts a
 * run once it is complete.
 *
 * <p>A different backend only has to implement {@link Results}; no caller touches anything else.
 */
public final class ResultStore implements Results {

    private final Path root;
    private final Object lock = new Object();
    private final Map<String, LinkedHashMap<String, Map<String, Object>>> caseIndex = new LinkedHashMap<>();

    public ResultStore(Path path) {
        this.root = path.toAbsolutePath().normalize();
        AtomicFiles.createDirectories(runsRoot());
        AtomicFiles.createDirectories(reviewsRoot());
        AtomicFiles.createDirectories(evolutionsRoot());
        AtomicFiles.createDirectories(locksRoot());
    }

    public Path root() {
        return root;
    }

    private Path runsRoot() {
        return root.resolve("runs");
    }

    private Path reviewsRoot() {
        return root.resolve("reviews");
    }

    private Path evolutionsRoot() {
        return root.resolve("evolutions");
    }

    private Path locksRoot() {
        return root.resolve(".locks");
    }

    private Path runDir(String runId) {
        return runsRoot().resolve(Names.safeName(runId, "run_id"));
    }

    private static String now() {
        return Instant.now().toString();
    }

    /** Holds the run's cross-process lock for the duration of a suite execution. */
    @Override
    public Handle lockRun(String runId) {
        String safe = runId == null ? "" : runId.trim();
        if (safe.isEmpty() || !Path.of(safe).getFileName().toString().equals(safe)) {
            throw new IllegalArgumentException("run_id must be a safe non-empty name");
        }
        return new Handle(ExclusiveFileLock.acquire(locksRoot().resolve(safe + ".lock"), "evaluation run " + runId));
    }

    /** Auto-closeable wrapper so callers can use try-with-resources. */
    public static final class Handle implements AutoCloseable {

        private final ExclusiveFileLock lock;

        private Handle(ExclusiveFileLock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            lock.close();
        }
    }

    @Override
    public void startRun(String runId, String adapter, String suite, String source, Map<String, Object> metadata) {
        startRun(runId, adapter, suite, source, metadata, false, null);
    }

    @Override
    public void startRun(
            String runId,
            String adapter,
            String suite,
            String source,
            Map<String, Object> metadata,
            boolean resume,
            Map<String, String> caseManifest) {
        synchronized (lock) {
            Path directory = runDir(runId);
            Path runFile = directory.resolve("run.json");
            Map<String, Object> existing = Files.isRegularFile(runFile) ? Json.parseObject(AtomicFiles.readText(runFile)) : null;
            if (existing != null) {
                if (!resume) {
                    throw new IllegalArgumentException("run already exists; use resume to continue: " + runId);
                }
                if (!Objects.equals(existing.get("adapter"), adapter)
                        || !Objects.equals(existing.get("suite"), suite)
                        || !Objects.equals(existing.get("source"), source)
                        || !Json.normalize(withoutCaseCount(asMap(existing.get("metadata_json"))))
                                .equals(Json.normalize(withoutCaseCount(metadata)))) {
                    throw new IllegalArgumentException(
                            "resume run identity or execution configuration changed");
                }
                if (caseManifest != null) {
                    verifyResumeManifest(directory, runId, caseManifest);
                }
                existing.put("status", "running");
                existing.put("finished_at", null);
                existing.put("metadata_json", metadata);
                AtomicFiles.writeText(runFile, Json.write(existing, 2));
                return;
            }

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("run_id", runId);
            record.put("adapter", adapter);
            record.put("suite", suite);
            record.put("source", source);
            record.put("status", "running");
            record.put("started_at", now());
            record.put("finished_at", null);
            record.put("metadata_json", metadata);
            AtomicFiles.writeText(runFile, Json.write(record, 2));
            if (caseManifest != null) {
                AtomicFiles.writeText(
                        directory.resolve("manifest.json"), Json.write(new TreeMap<>(caseManifest), 2));
            }
        }
    }

    private void verifyResumeManifest(Path directory, String runId, Map<String, String> caseManifest) {
        Path manifestFile = directory.resolve("manifest.json");
        Map<String, Object> storedRaw =
                Files.isRegularFile(manifestFile) ? Json.parseObject(AtomicFiles.readText(manifestFile)) : Map.of();
        Map<String, String> stored = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : storedRaw.entrySet()) {
            stored.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        int savedCount = loadCases(runId).size();
        if (stored.isEmpty() && savedCount > 0) {
            throw new IllegalArgumentException("cannot safely resume a legacy run without case identities");
        }
        List<String> missing = new ArrayList<>();
        for (String caseId : stored.keySet()) {
            if (!caseManifest.containsKey(caseId)) {
                missing.add(caseId);
            }
        }
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            if (caseManifest.containsKey(entry.getKey())
                    && !caseManifest.get(entry.getKey()).equals(entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        missing.sort(Comparator.naturalOrder());
        changed.sort(Comparator.naturalOrder());
        if (!missing.isEmpty() || !changed.isEmpty()) {
            List<String> details = new ArrayList<>();
            if (!missing.isEmpty()) {
                details.add("missing case_id: " + String.join(", ", missing));
            }
            if (!changed.isEmpty()) {
                details.add("changed case_id: " + String.join(", ", changed));
            }
            throw new IllegalArgumentException("resume dataset changed; " + String.join("; ", details));
        }
        Map<String, String> merged = new LinkedHashMap<>(stored);
        for (Map.Entry<String, String> entry : caseManifest.entrySet()) {
            merged.putIfAbsent(entry.getKey(), entry.getValue());
        }
        AtomicFiles.writeText(directory.resolve("manifest.json"), Json.write(new TreeMap<>(merged), 2));
    }

    /** Writes the final status and compacts the run's append-only case log. */
    @Override
    public void finishRun(String runId, String status) {
        synchronized (lock) {
            Path directory = runDir(runId);
            Path runFile = directory.resolve("run.json");
            Map<String, Object> record = Json.parseObject(AtomicFiles.readText(runFile));
            record.put("status", status);
            record.put("finished_at", now());
            AtomicFiles.writeText(runFile, Json.write(record, 2));
            LinkedHashMap<String, Map<String, Object>> cases = loadCases(runId);
            List<String> lines = new ArrayList<>(cases.size());
            for (Map<String, Object> value : cases.values()) {
                lines.add(Json.write(value));
            }
            AtomicFiles.writeText(directory.resolve("cases.jsonl"), lines.isEmpty() ? "" : String.join("\n", lines) + "\n");
        }
    }

    @Override
    public boolean hasCase(String runId, String caseId) {
        synchronized (lock) {
            return loadCases(runId).containsKey(caseId);
        }
    }

    @Override
    public void saveCase(CaseResult result) {
        saveCases(List.of(result));
    }

    /** Persists a batch of results; the caller bounds the batch size so a crash replays at most one. */
    @Override
    public void saveCases(List<CaseResult> results) {
        if (results.isEmpty()) {
            return;
        }
        synchronized (lock) {
            String runId = results.get(0).runId();
            LinkedHashMap<String, Map<String, Object>> cases = loadCases(runId);
            StringBuilder appended = new StringBuilder();
            for (CaseResult result : results) {
                // Stored and cached in normalized form so an in-memory read and a re-read from disk are
                // indistinguishable; callers read plain maps, never live model objects.
                Map<String, Object> payload = asMap(Json.normalize(result.toJson()));
                payload.put("hard_pass", result.hardPass());
                payload.put("soft_warning_count", result.softWarningCount());
                cases.put(result.evalCase().caseId(), payload);
                appended.append(Json.write(payload)).append('\n');
            }
            AtomicFiles.appendText(runDir(runId).resolve("cases.jsonl"), appended.toString());
        }
    }

    @Override
    public List<Map<String, Object>> listResults(String runId) {
        synchronized (lock) {
            return new ArrayList<>(loadCases(runId).values());
        }
    }

    /** Adds a check that could only be decided once the whole run was visible. */
    @Override
    public void addCaseCheck(String runId, String caseId, CheckResult check) {
        synchronized (lock) {
            LinkedHashMap<String, Map<String, Object>> cases = loadCases(runId);
            Map<String, Object> record = cases.get(caseId);
            if (record == null) {
                throw new IllegalArgumentException("cannot add a check to missing result: " + runId + "/" + caseId);
            }
            Object rawChecks = record.get("checks");
            if (!(rawChecks instanceof List<?>)) {
                throw new IllegalStateException("stored result has no checks array: " + runId + "/" + caseId);
            }
            @SuppressWarnings("unchecked")
            List<Object> checks = (List<Object>) rawChecks;
            Map<String, Object> payload = asMap(check.toJson());
            if (checks.contains(payload)) {
                return;
            }
            checks.add(payload);
            if ("hard".equals(check.level()) && !check.passed()) {
                record.put("hard_pass", false);
            } else if (("soft".equals(check.level()) || "candidate".equals(check.level())) && !check.passed()) {
                record.put("soft_warning_count", intValue(record.get("soft_warning_count")) + 1);
            }
            AtomicFiles.appendLine(runDir(runId).resolve("cases.jsonl"), Json.write(record));
        }
    }

    @Override
    public void saveReview(String runId, String caseId, String decision, String finalConclusion) {
        synchronized (lock) {
            if (!loadCases(runId).containsKey(caseId)) {
                throw new IllegalArgumentException("cannot review missing result: " + runId + "/" + caseId);
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("run_id", runId);
            record.put("case_id", caseId);
            record.put("decision", decision);
            record.put("final_conclusion", finalConclusion);
            record.put("reviewed_at", now());
            AtomicFiles.appendLine(reviewsRoot().resolve(Names.safeName(runId, "run_id") + ".jsonl"), Json.write(record));
        }
    }

    /** Joins stored reviews with their case results, newest review last. */
    @Override
    public List<Map<String, Object>> listReviewedResults(String decision) {
        synchronized (lock) {
            List<Map<String, Object>> reviews = new ArrayList<>();
            List<Path> reviewFiles = new ArrayList<>();
            try (Stream<Path> paths = Files.isDirectory(reviewsRoot()) ? Files.list(reviewsRoot()) : null) {
                if (paths != null) {
                    paths.filter(file -> file.getFileName().toString().endsWith(".jsonl")).forEach(reviewFiles::add);
                }
            } catch (IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
            for (Path path : reviewFiles) {
                for (String line : AtomicFiles.readText(path).split("\n", -1)) {
                    if (!line.isBlank()) {
                        reviews.add(Json.parseObject(line));
                    }
                }
            }
            Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
            for (Map<String, Object> review : reviews) {
                latest.put(review.get("run_id") + "\0" + review.get("case_id"), review);
            }
            List<Map<String, Object>> selected = new ArrayList<>();
            for (Map<String, Object> review : latest.values()) {
                if (Objects.equals(review.get("decision"), decision)) {
                    selected.add(review);
                }
            }
            selected.sort(
                    Comparator.comparing((Map<String, Object> review) -> String.valueOf(review.get("reviewed_at")))
                            .thenComparing(review -> String.valueOf(review.get("case_id"))));

            List<Map<String, Object>> results = new ArrayList<>();
            for (Map<String, Object> review : selected) {
                String runId = String.valueOf(review.get("run_id"));
                Map<String, Object> record = loadCases(runId).get(String.valueOf(review.get("case_id")));
                if (record == null) {
                    continue;
                }
                Map<String, Object> copy = new LinkedHashMap<>(record);
                copy.put("human_final_conclusion", review.get("final_conclusion"));
                results.add(copy);
            }
            return results;
        }
    }

    @Override
    public void saveEvolution(String experimentId, String candidateId, String decision, Map<String, Object> result) {
        synchronized (lock) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("experiment_id", experimentId);
            record.put("candidate_id", candidateId);
            record.put("decision", decision);
            record.put("result_json", result);
            record.put("created_at", now());
            AtomicFiles.writeText(
                    evolutionsRoot().resolve(Names.safeName(experimentId, "experiment_id") + ".json"),
                    Json.write(record, 2));
        }
    }

    @Override
    public Map<String, Object> getEvolution(String experimentId) {
        synchronized (lock) {
            Path path = evolutionsRoot().resolve(Names.safeName(experimentId, "experiment_id") + ".json");
            if (!Files.isRegularFile(path)) {
                return null;
            }
            return asMap(Json.parseObject(AtomicFiles.readText(path)).get("result_json"));
        }
    }

    /** Reads the run's case log, applying last-write-wins and ordering by case id. */
    private LinkedHashMap<String, Map<String, Object>> loadCases(String runId) {
        LinkedHashMap<String, Map<String, Object>> cached = caseIndex.get(runId);
        if (cached != null) {
            return cached;
        }
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        Path path = runDir(runId).resolve("cases.jsonl");
        if (Files.isRegularFile(path)) {
            for (String line : AtomicFiles.readText(path).split("\n", -1)) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> record;
                try {
                    record = Json.parseObject(line);
                } catch (RuntimeException truncatedTail) {
                    // A crash can cut the final line in half; the batch it belonged to is replayed on resume.
                    break;
                }
                String caseId = String.valueOf(asMap(record.get("case")).get("case_id"));
                latest.put(caseId, record);
            }
        }
        List<Map.Entry<String, Map<String, Object>>> entries = new ArrayList<>(latest.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        LinkedHashMap<String, Map<String, Object>> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : entries) {
            ordered.put(entry.getKey(), entry.getValue());
        }
        caseIndex.put(runId, ordered);
        return ordered;
    }

    private static Map<String, Object> withoutCaseCount(Map<String, Object> metadata) {
        Map<String, Object> result = new LinkedHashMap<>(metadata);
        result.remove("case_count");
        return result;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @Override
    public void close() {
        caseIndex.clear();
    }
}
