package io.assay.cli;

import io.assay.bootstrap.Stores;
import io.assay.json.Json;
import io.assay.runner.Job;
import io.assay.runner.JobStore;
import io.assay.store.Results;
import io.assay.support.AtomicFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Lists, cancels, replays, reindexes, and prunes durable jobs and their report artifacts. */
final class QueueAdminCommands {

    private QueueAdminCommands() {
    }

    static int jobs(CommandLine.Namespace args) {
        try (JobStore store = QueueSupport.jobStore(args)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Job job : store.list(args.integer("limit"))) {
                rows.add(jobMap(job));
            }
            System.out.println(Json.write(rows));
            return 0;
        }
    }

    static int cancelJob(CommandLine.Namespace args) {
        try (JobStore store = QueueSupport.jobStore(args)) {
            String jobId = args.string("job-id");
            boolean cancelled = store.cancel(jobId);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("job_id", jobId);
            output.put("cancelled", cancelled);
            store.find(jobId).ifPresent(job -> output.put("status", job.status().name()));
            System.out.println(Json.write(output));
            return cancelled ? 0 : 1;
        }
    }

    static int replayJob(CommandLine.Namespace args) {
        try (JobStore store = QueueSupport.jobStore(args)) {
            String jobId = args.string("job-id");
            Optional<Job> replayed =
                    store.replay(jobId, args.integer("max-attempts"), args.integer("max-replays"));
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("job_id", jobId);
            output.put("replayed", replayed.isPresent());
            replayed.ifPresent(job -> output.put("status", job.status().name()));
            replayed.ifPresent(job -> output.put("replay_count", job.replayCount()));
            System.out.println(Json.write(output));
            return replayed.isPresent() ? 0 : 1;
        }
    }

    static int reindexJobs(CommandLine.Namespace args) {
        try (JobStore store = QueueSupport.jobStore(args)) {
            int indexed;
            try {
                indexed = store.rebuildRunIdIndex();
            } catch (UnsupportedOperationException unsupported) {
                throw new CommandLine.UsageException(
                        "this job store does not support run id index rebuilds", 2, false);
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("store", args.string("store"));
            output.put("indexed", indexed);
            System.out.println(Json.write(output));
            return 0;
        }
    }

    static int prune(CommandLine.Namespace args) {
        int keepDays = args.integer("keep-days");
        int keepRuns = args.integer("keep-runs");
        int keepJobs = args.integer("keep-jobs");
        if (keepDays < 0 || keepRuns < 0 || keepJobs < 0) {
            throw new CommandLine.UsageException("prune limits must be non-negative", 2, false);
        }
        boolean dryRun = args.flag("dry-run");
        Instant cutoff = Instant.now().minus(Duration.ofDays(keepDays));
        List<String> runs;
        List<String> jobs;
        try (Results results = Stores.open(args.string("store"));
                JobStore jobsStore = QueueSupport.jobStore(args)) {
            runs = results.pruneRuns(cutoff, keepRuns, dryRun);
            jobs = jobsStore.pruneJobs(cutoff, keepJobs, dryRun);
        }
        int artifacts = pruneOutputArtifacts(args.path("output"), cutoff, keepRuns, dryRun);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("cutoff", cutoff.toString());
        output.put("dry_run", dryRun);
        output.put("runs", runs);
        output.put("jobs", jobs);
        output.put("artifacts", artifacts);
        System.out.println(Json.write(output));
        return 0;
    }

    private static int pruneOutputArtifacts(
            Path output, Instant cutoff, int keepLatest, boolean dryRun) {
        if (output == null || !Files.isDirectory(output)) {
            return 0;
        }
        List<Path> directories;
        try (Stream<Path> stream = Files.list(output)) {
            directories = stream.filter(Files::isDirectory)
                    .filter(QueueAdminCommands::isRunArtifactDirectory)
                    .sorted(Comparator.comparing(QueueAdminCommands::lastModified).reversed())
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        int deleted = 0;
        for (int index = keepLatest; index < directories.size(); index++) {
            Path directory = directories.get(index);
            if (!lastModified(directory).isBefore(cutoff)) {
                continue;
            }
            deleted++;
            if (!dryRun) {
                AtomicFiles.deleteRecursively(directory);
            }
        }
        return deleted;
    }

    private static Instant lastModified(Path directory) {
        Instant newest = null;
        for (String artifact : List.of("results.json", "report.md")) {
            Path file = directory.resolve(artifact);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            Instant modified = modified(file);
            if (newest == null || modified.isAfter(newest)) {
                newest = modified;
            }
        }
        return newest != null ? newest : modified(directory);
    }

    private static Instant modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** A report directory, not an arbitrary directory that happens to live under --output. */
    private static boolean isRunArtifactDirectory(Path directory) {
        return Files.isRegularFile(directory.resolve("results.json"))
                || Files.isRegularFile(directory.resolve("report.md"));
    }

    private static Map<String, Object> jobMap(Job job) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("job_id", job.id());
        row.put("status", job.status().name());
        row.put("attempts", job.attempts());
        row.put("max_attempts", job.maxAttempts());
        row.put("lease_owner", job.leaseOwner());
        row.put("lease_expires_at", job.leaseExpiresAt() == null ? null : job.leaseExpiresAt().toString());
        row.put("cancel_requested", job.cancelRequested());
        row.put("created_at", job.createdAt().toString());
        row.put("started_at", job.startedAt() == null ? null : job.startedAt().toString());
        row.put("finished_at", job.finishedAt() == null ? null : job.finishedAt().toString());
        row.put("error", job.error());
        row.put("idempotency_key", job.idempotencyKey());
        row.put("replay_count", job.replayCount());
        row.put("next_attempt_at", job.nextAttemptAt() == null ? null : job.nextAttemptAt().toString());
        return row;
    }
}
