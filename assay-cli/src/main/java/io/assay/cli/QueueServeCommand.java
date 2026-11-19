package io.assay.cli;

import io.assay.runner.JobStore;
import io.assay.web.DashboardServer;
import java.awt.Desktop;
import java.io.IOException;
import java.net.BindException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts the local dashboard, optionally with the durable worker attached. */
final class QueueServeCommand {

    private QueueServeCommand() {
    }

    static int serve(CommandLine.Namespace args) {
        JobStore jobs = args.flag("worker") ? QueueSupport.jobStore(args) : null;
        DashboardServer server = null;
        Thread workerThread = null;
        AtomicBoolean workerRunning = new AtomicBoolean(true);
        try {
            server = startDashboard(args, jobs);
            Thread shutdown = new Thread(server::close, "assay-dashboard-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                System.out.println("dashboard: " + server.uri());
                System.out.println("reports: " + server.output());
                if (jobs != null) {
                    int leaseSeconds = Math.max(1, args.integer("lease-seconds"));
                    long pollMillis =
                            Math.max(250, Math.round(Math.max(0.25, args.decimal("poll")) * 1000));
                    String workerId = args.string("worker-id");
                    if (workerId == null || workerId.isBlank()) {
                        workerId = "dashboard-worker-" + CliIds.shortId();
                    }
                    String finalWorkerId = workerId;
                    workerThread = new Thread(
                            () -> QueueWorkerCommand.runWorker(
                                    jobs, finalWorkerId, leaseSeconds, pollMillis, false,
                                    workerRunning::get),
                            "assay-dashboard-worker");
                    workerThread.setDaemon(true);
                    workerThread.start();
                    System.out.println(
                            "queue worker: " + workerId + " (store: " + args.string("store") + ")");
                } else {
                    System.out.println("Read-only local viewer. Use --worker to enable the job queue.");
                }
                System.out.println("Press Ctrl+C to stop.");
                if (args.flag("open-browser")) {
                    openBrowser(server);
                }
                server.await();
                return 0;
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException shuttingDown) {
                    // The hook owns shutdown when the JVM is already stopping.
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (IOException error) {
            throw new IllegalStateException("cannot start dashboard: " + error.getMessage(), error);
        } finally {
            workerRunning.set(false);
            if (workerThread != null) {
                workerThread.interrupt();
                try {
                    workerThread.join(5000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (server != null) {
                server.close();
            }
            if (jobs != null) {
                jobs.close();
            }
        }
    }

    private static DashboardServer startDashboard(CommandLine.Namespace args, JobStore jobs)
            throws IOException {
        try {
            return new DashboardServer(
                    args.path("output"), args.integer("port"), jobs, args.string("store"));
        } catch (BindException busy) {
            if (!args.flag("open-browser") || args.integer("port") == 0) {
                throw busy;
            }
            System.out.println("Requested port is busy; selecting an available local port.");
            return new DashboardServer(args.path("output"), 0, jobs, args.string("store"));
        }
    }

    private static void openBrowser(DashboardServer server) {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new UnsupportedOperationException();
            }
            Desktop.getDesktop().browse(server.uri());
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            System.out.println("Open the dashboard URL above in your browser.");
        }
    }
}
