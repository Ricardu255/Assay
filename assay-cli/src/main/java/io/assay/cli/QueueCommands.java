package io.assay.cli;

import io.assay.runner.JobStore;
import io.assay.runner.RetryPolicy;
import java.util.function.BooleanSupplier;

/** Stable entry point for the dashboard and durable-queue commands. */
final class QueueCommands {

    private QueueCommands() {
    }

    static int serve(CommandLine.Namespace args) {
        return QueueServeCommand.serve(args);
    }

    static int submit(CommandLine.Namespace args) {
        return QueueSubmitCommand.submit(args);
    }

    static int jobs(CommandLine.Namespace args) {
        return QueueAdminCommands.jobs(args);
    }

    static int cancelJob(CommandLine.Namespace args) {
        return QueueAdminCommands.cancelJob(args);
    }

    static int replayJob(CommandLine.Namespace args) {
        return QueueAdminCommands.replayJob(args);
    }

    static int reindexJobs(CommandLine.Namespace args) {
        return QueueAdminCommands.reindexJobs(args);
    }

    static int prune(CommandLine.Namespace args) {
        return QueueAdminCommands.prune(args);
    }

    static int worker(CommandLine.Namespace args) {
        return QueueWorkerCommand.worker(args);
    }

    static int runWorker(
            JobStore store, String workerId, int leaseSeconds, long pollMillis, boolean once) {
        return QueueWorkerCommand.runWorker(store, workerId, leaseSeconds, pollMillis, once);
    }

    static int runWorker(
            JobStore store,
            String workerId,
            int leaseSeconds,
            long pollMillis,
            boolean once,
            BooleanSupplier running) {
        return QueueWorkerCommand.runWorker(
                store, workerId, leaseSeconds, pollMillis, once, running);
    }

    static RetryPolicy retryPolicy(CommandLine.Namespace args) {
        return QueueSupport.retryPolicy(args);
    }
}
