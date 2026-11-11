package io.assay.store.file;

import io.assay.json.Json;
import io.assay.runner.IdempotencyConflictException;
import io.assay.runner.Job;
import io.assay.runner.JobStatus;
import io.assay.runner.JobStore;
import io.assay.runner.RetryPolicy;
import io.assay.support.AtomicFiles;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Zero-dependency file-backed job queue for a single machine.
 *
 * <p>All queue mutations take one short process-wide file lock. The lock is released immediately
 * after a claim; the lease in the job file, not the lock, coordinates workers during execution.
 */
public final class FileJobStore implements JobStore {

    private final Path jobs;
    private final Path runIndex;
    private final Path lockFile;
    private final RetryPolicy retryPolicy;

    public FileJobStore(Path root) {
        this(root, RetryPolicy.defaults());
    }

    public FileJobStore(Path root, RetryPolicy retryPolicy) {
        jobs = root.resolve("jobs");
        runIndex = root.resolve("run-index");
        lockFile = root.resolve(".jobs.lock");
        this.retryPolicy = retryPolicy == null ? RetryPolicy.defaults() : retryPolicy;
        AtomicFiles.createDirectories(jobs);
        AtomicFiles.createDirectories(runIndex);
    }

    @Override
    public Job submit(String idempotencyKey, Map<String, Object> payload, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        Instant now = Instant.now();
        return locked(() -> {
            if (key != null) {
                for (Path file : files()) {
                    Job existing = read(file);
                    if (key.equals(existing.idempotencyKey())) {
                        requireSamePayload(key, existing, payload);
                        return existing;
                    }
                }
            }
            Job job = new Job(
                    "job-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                    JobStatus.QUEUED, payload, 0, maxAttempts, null, null, false, now, null, null, null,
                    key, 0, null);
            write(job);
            index(job);
            return job;
        });
    }

    @Override
    public Optional<Job> findByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        String wanted = runId.trim();
        return locked(() -> {
            Path pointer = indexFile(wanted);
            if (Files.isRegularFile(pointer)) {
                Path file = path(AtomicFiles.readText(pointer).trim());
                if (Files.isRegularFile(file)) {
                    Job job = read(file);
                    if (wanted.equals(runId(job))) {
                        return Optional.of(job);
                    }
                }
            }
            // Stores created before the index existed fall back to a scan that keeps the newest job
            // when a run id has more than one job record.
            Job newest = null;
            for (Path file : files()) {
                Job job = read(file);
                if (wanted.equals(runId(job))
                        && (newest == null || job.createdAt().isAfter(newest.createdAt()))) {
                    newest = job;
                }
            }
            return Optional.ofNullable(newest);
        });
    }

    /**
     * Rebuilds the run_id index from the job payloads.
     *
     * <p>Useful after upgrading a store created before the index existed, or after removing
     * index files. Returns the number of indexed jobs.
     */
    @Override
    public int rebuildRunIdIndex() {
        return locked(() -> {
            try (Stream<Path> stream = Files.list(runIndex)) {
                for (Path pointer : stream.toList()) {
                    Files.deleteIfExists(pointer);
                }
            }
            List<Job> jobs = new ArrayList<>();
            for (Path file : files()) {
                Job job = read(file);
                if (runId(job) != null) {
                    jobs.add(job);
                }
            }
            // Oldest first so a newer job for the same run id overwrites the pointer.
            jobs.sort(Comparator.comparing(Job::createdAt));
            for (Job job : jobs) {
                index(job);
            }
            return jobs.size();
        });
    }

    @Override
    public Optional<Job> claim(String workerId, Duration lease) {
        String worker = text(workerId, "workerId");
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        return locked(() -> {
            Instant now = Instant.now();
            List<Job> candidates = new ArrayList<>();
            for (Path file : files()) {
                Job job = read(file);
                if (job.status() == JobStatus.QUEUED && job.cancelRequested()) {
                    write(cancelled(job, now));
                } else if (job.status() == JobStatus.QUEUED) {
                    if (ready(job, now)) {
                        candidates.add(job);
                    }
                } else if (job.status() == JobStatus.RUNNING && expired(job, now)) {
                    if (job.attempts() >= job.maxAttempts()) {
                        write(failed(job, now, "lease expired after max attempts"));
                    } else {
                        Job queued = withStatus(job, JobStatus.QUEUED, null, null, null, job.error(), null);
                        write(queued);
                        candidates.add(queued);
                    }
                }
            }
            candidates.sort(Comparator.comparing(Job::createdAt));
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            Job next = candidates.get(0);
            Job running = new Job(
                    next.id(), JobStatus.RUNNING, next.payload(), next.attempts() + 1, next.maxAttempts(),
                    worker, now.plus(lease), next.cancelRequested(), next.createdAt(),
                    next.startedAt() == null ? now : next.startedAt(), null, null,
                    next.idempotencyKey(), next.replayCount(), null);
            write(running);
            return Optional.of(running);
        });
    }

    @Override
    public boolean heartbeat(String jobId, String workerId, Duration lease) {
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        return locked(() -> {
            Path file = path(jobId);
            if (!Files.isRegularFile(file)) {
                return false;
            }
            Job job = read(file);
            if (job.status() != JobStatus.RUNNING || !workerId.equals(job.leaseOwner())) {
                return false;
            }
            write(new Job(
                    job.id(), job.status(), job.payload(), job.attempts(), job.maxAttempts(),
                    job.leaseOwner(), Instant.now().plus(lease), job.cancelRequested(), job.createdAt(),
                    job.startedAt(), job.finishedAt(), job.error(), job.idempotencyKey(), job.replayCount(),
                    job.nextAttemptAt()));
            return true;
        });
    }

    @Override
    public boolean cancel(String jobId) {
        return locked(() -> {
            Path file = path(jobId);
            if (!Files.isRegularFile(file)) {
                return false;
            }
            Job job = read(file);
            if (job.status() == JobStatus.QUEUED) {
                write(cancelled(job, Instant.now()));
                return true;
            }
            if (job.status() == JobStatus.RUNNING) {
                write(new Job(
                        job.id(), job.status(), job.payload(), job.attempts(), job.maxAttempts(),
                        job.leaseOwner(), job.leaseExpiresAt(), true, job.createdAt(),
                        job.startedAt(), job.finishedAt(), job.error(), job.idempotencyKey(), job.replayCount(),
                        job.nextAttemptAt()));
                return true;
            }
            return false;
        });
    }

    @Override
    public boolean isCancelRequested(String jobId) {
        return locked(() -> {
            Path file = path(jobId);
            return Files.isRegularFile(file) && read(file).cancelRequested();
        });
    }

    @Override
    public Optional<Job> find(String jobId) {
        return locked(() -> {
            Path file = path(jobId);
            return Files.isRegularFile(file) ? Optional.of(read(file)) : Optional.empty();
        });
    }

    @Override
    public List<Job> list(int limit) {
        int max = Math.max(1, Math.min(MAX_LIST_LIMIT, limit));
        return locked(() -> {
            List<Job> jobs = new ArrayList<>();
            for (Path file : files()) {
                jobs.add(read(file));
            }
            jobs.sort(Comparator.comparing(Job::createdAt).reversed());
            return jobs.subList(0, Math.min(max, jobs.size()));
        });
    }

    @Override
    public void succeed(String jobId, String workerId) {
        finish(jobId, workerId, JobStatus.SUCCEEDED, null);
    }

    @Override
    public void fail(String jobId, String workerId, String error) {
        locked(() -> {
            Path file = path(jobId);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            Job job = read(file);
            if (job.status() != JobStatus.RUNNING || !workerId.equals(job.leaseOwner())) {
                return null;
            }
            if (job.attempts() < job.maxAttempts()) {
                Instant now = Instant.now();
                Duration delay = retryPolicy.delayFor(job.attempts());
                write(new Job(
                        job.id(), JobStatus.QUEUED, job.payload(), job.attempts(), job.maxAttempts(),
                        null, null, job.cancelRequested(), job.createdAt(), job.startedAt(), null, error,
                        job.idempotencyKey(), job.replayCount(),
                        delay.isZero() ? null : now.plus(delay)));
            } else {
                write(failed(job, Instant.now(), error));
            }
            return null;
        });
    }

    @Override
    public void finish(String jobId, String workerId, JobStatus status, String error) {
        if (status == JobStatus.QUEUED || status == JobStatus.RUNNING) {
            throw new IllegalArgumentException("finish requires a terminal status");
        }
        locked(() -> {
            Path file = path(jobId);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            Job job = read(file);
            if (job.status() == JobStatus.RUNNING && workerId.equals(job.leaseOwner())) {
                write(new Job(
                        job.id(), status, job.payload(), job.attempts(), job.maxAttempts(),
                        null, null, job.cancelRequested(), job.createdAt(), job.startedAt(),
                        Instant.now(), error, job.idempotencyKey(), job.replayCount(), null));
            }
            return null;
        });
    }

    @Override
    public Optional<Job> replay(String jobId, int maxAttempts, int maxReplays) {
        if (maxAttempts < 1 || maxReplays < 1) {
            throw new IllegalArgumentException("maxAttempts and maxReplays must be positive");
        }
        return locked(() -> {
            Path file = path(jobId);
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            Job job = read(file);
            if (job.status() != JobStatus.FAILED || job.replayCount() >= maxReplays) {
                return Optional.empty();
            }
            Job queued = new Job(
                    job.id(), JobStatus.QUEUED, job.payload(), 0, maxAttempts, null, null,
                    false, job.createdAt(), null, null, null, job.idempotencyKey(), job.replayCount() + 1,
                    null);
            write(queued);
            return Optional.of(queued);
        });
    }

    @Override
    public List<String> pruneJobs(Instant cutoff, int keepLatest, boolean dryRun) {
        if (cutoff == null) {
            throw new IllegalArgumentException("cutoff must not be null");
        }
        if (keepLatest < 0) {
            throw new IllegalArgumentException("keepLatest must be non-negative");
        }
        return locked(() -> {
            List<Job> terminal = new ArrayList<>();
            for (Path file : files()) {
                Job job = read(file);
                if (job.status() == JobStatus.SUCCEEDED
                        || job.status() == JobStatus.FAILED
                        || job.status() == JobStatus.CANCELLED) {
                    terminal.add(job);
                }
            }
            terminal.sort(Comparator.comparing(Job::createdAt).reversed());
            List<String> deleted = new ArrayList<>();
            for (int index = keepLatest; index < terminal.size(); index++) {
                Job job = terminal.get(index);
                if (!job.createdAt().isBefore(cutoff)) {
                    continue;
                }
                deleted.add(job.id());
                if (!dryRun) {
                    Files.deleteIfExists(path(job.id()));
                    unindex(job);
                }
            }
            return deleted;
        });
    }

    @Override
    public void close() {
        // No open resources.
    }

    private List<Path> files() throws IOException {
        try (Stream<Path> stream = Files.list(jobs)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }

    private Path path(String jobId) {
        return jobs.resolve(text(jobId, "jobId") + ".json");
    }

    private Path indexFile(String runId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(runId.getBytes(StandardCharsets.UTF_8));
            return runIndex.resolve(HexFormat.of().formatHex(digest) + ".job");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private void index(Job job) throws IOException {
        String runId = runId(job);
        if (runId != null) {
            AtomicFiles.writeText(indexFile(runId), job.id() + "\n");
        }
    }

    private void unindex(Job job) throws IOException {
        String runId = runId(job);
        if (runId == null) {
            return;
        }
        Path pointer = indexFile(runId);
        if (Files.isRegularFile(pointer) && job.id().equals(AtomicFiles.readText(pointer).trim())) {
            Files.deleteIfExists(pointer);
        }
    }

    private static String runId(Job job) {
        Object value = job.payload() == null ? null : job.payload().get("run_id");
        return value == null ? null : String.valueOf(value);
    }

    private Job read(Path file) throws IOException {
        Map<String, Object> value = Json.parseObject(AtomicFiles.readText(file));
        Map<String, Object> payload = value.get("payload") instanceof Map<?, ?> map
                ? cast(map)
                : Map.of();
        return new Job(
                String.valueOf(value.get("job_id")),
                JobStatus.valueOf(String.valueOf(value.get("status"))),
                payload,
                integer(value.get("attempts")),
                integer(value.get("max_attempts")),
                nullable(value.get("lease_owner")),
                instant(value.get("lease_expires_at")),
                Boolean.TRUE.equals(value.get("cancel_requested")),
                instant(value.get("created_at")),
                instant(value.get("started_at")),
                instant(value.get("finished_at")),
                nullable(value.get("error")),
                nullable(value.get("idempotency_key")),
                integerOrZero(value.get("replay_count")),
                instant(value.get("next_attempt_at")));
    }

    private void write(Job job) throws IOException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("job_id", job.id());
        value.put("status", job.status().name());
        value.put("payload", job.payload());
        value.put("attempts", job.attempts());
        value.put("max_attempts", job.maxAttempts());
        value.put("lease_owner", job.leaseOwner());
        value.put("lease_expires_at", job.leaseExpiresAt() == null ? null : job.leaseExpiresAt().toString());
        value.put("cancel_requested", job.cancelRequested());
        value.put("created_at", job.createdAt().toString());
        value.put("started_at", job.startedAt() == null ? null : job.startedAt().toString());
        value.put("finished_at", job.finishedAt() == null ? null : job.finishedAt().toString());
        value.put("error", job.error());
        value.put("idempotency_key", job.idempotencyKey());
        value.put("replay_count", job.replayCount());
        value.put("next_attempt_at", job.nextAttemptAt() == null ? null : job.nextAttemptAt().toString());
        AtomicFiles.writeText(path(job.id()), Json.write(value, 2) + "\n");
    }

    private static Job withStatus(
            Job job, JobStatus status, String owner, Instant lease, Instant finished, String error,
            Instant nextAttemptAt) {
        return new Job(
                job.id(), status, job.payload(), job.attempts(), job.maxAttempts(), owner, lease,
                job.cancelRequested(), job.createdAt(), job.startedAt(), finished, error,
                job.idempotencyKey(), job.replayCount(), nextAttemptAt);
    }

    private static Job cancelled(Job job, Instant finished) {
        return withStatus(job, JobStatus.CANCELLED, null, null, finished, "cancelled by user", null);
    }

    private static Job failed(Job job, Instant finished, String error) {
        return withStatus(job, JobStatus.FAILED, null, null, finished, error, null);
    }

    private static boolean ready(Job job, Instant now) {
        return job.nextAttemptAt() == null || !job.nextAttemptAt().isAfter(now);
    }

    private static boolean expired(Job job, Instant now) {
        return job.leaseExpiresAt() != null && job.leaseExpiresAt().isBefore(now);
    }

    private static void requireSamePayload(String key, Job existing, Map<String, Object> payload) {
        if (!Json.canonical(existing.payload()).equals(Json.canonical(payload))) {
            throw new IdempotencyConflictException(key, existing.id());
        }
    }

    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static int integerOrZero(Object value) {
        return value == null ? 0 : integer(value);
    }

    private static String nullable(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Instant instant(Object value) {
        return value == null ? null : Instant.parse(String.valueOf(value));
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-empty");
        }
        return value.trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    private <T> T locked(LockedAction<T> action) {
        while (true) {
            try (FileChannel channel =
                            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                return action.run();
            } catch (OverlappingFileLockException busy) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for job lock", interrupted);
                }
            } catch (IOException error) {
                throw new IllegalStateException("cannot access job store", error);
            }
        }
    }

    @FunctionalInterface
    private interface LockedAction<T> {
        T run() throws IOException;
    }
}
