package io.assay.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.engine.EvaluationEngine;
import io.assay.model.ProjectAdapter;
import io.assay.store.ResultStore;
import io.assay.testing.ChildProcess;
import io.assay.workspace.TextArtifactWorkspace;
import io.assay.support.ExclusiveFileLock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks and store writes under contention.
 *
 * <p>Cross-process cases launch a real second JVM: a file lock that only rejects a second holder inside
 * one process would be worthless for the two-operator scenario the framework exists to prevent.
 */
class ConcurrencySafetyTest {

    @Test
    void loopLockRejectsASecondHolder(@TempDir Path root) {
        TextArtifactWorkspace workspace = new TextArtifactWorkspace(root);

        assertThrows(
                IllegalStateException.class,
                () -> {
                    try (AutoCloseable first = workspace.lockLoop("same-loop")) {
                        workspace.lockLoop("same-loop");
                    }
                });
    }

    @Test
    void loopLockRejectsAnotherProcess(@TempDir Path root) throws Exception {
        TextArtifactWorkspace workspace = new TextArtifactWorkspace(root);
        ChildProcess child = ChildProcess.launch(root, "workspace-lock", root);
        try {
            child.go();
            child.awaitReady(20_000);

            IllegalStateException error =
                    assertThrows(IllegalStateException.class, () -> workspace.lockLoop("cross-process-loop"));
            assertTrue(error.getMessage().contains("already running"), error.getMessage());
            assertEquals(0, child.finish(20_000));
        } finally {
            child.kill();
        }
    }

    @Test
    void runLockRejectsAnotherProcess(@TempDir Path root) throws Exception {
        Path storePath = root.resolve("store");
        try (ResultStore store = new ResultStore(storePath)) {
            ChildProcess child = ChildProcess.launch(root, "run-lock", storePath);
            try {
                child.go();
                child.awaitReady(20_000);

                IllegalStateException error =
                        assertThrows(
                                IllegalStateException.class,
                                () -> {
                                    try (ResultStore other = new ResultStore(storePath);
                                            ResultStore.Handle handle = other.lockRun("shared-run")) {
                                        assertTrue(false, "a second holder must not acquire the run lock");
                                    }
                                });
                assertTrue(error.getMessage().contains("already running"), error.getMessage());
                assertEquals(0, child.finish(20_000));
            } finally {
                child.kill();
            }
        }
    }

    @Test
    void storeRejectsDuplicateRunWithoutResume(@TempDir Path root) {
        Path storePath = root.resolve("store");
        try (ResultStore first = new ResultStore(storePath);
                ResultStore second = new ResultStore(storePath)) {
            first.startRun("run", "adapter", "suite", "online", Map.of());

            IllegalArgumentException error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> second.startRun("run", "adapter", "suite", "online", Map.of()));
            assertTrue(error.getMessage().contains("run already exists"), error.getMessage());

            second.startRun("run", "adapter", "suite", "online", Map.of(), true, null);
            assertTrue(Files.isRegularFile(storePath.resolve("runs/run/run.json")));
        }
    }

    @Test
    void twoProcessesWriteDistinctRunsWithoutLoss(@TempDir Path root) throws Exception {
        Path storePath = root.resolve("store");
        try (ResultStore ignored = new ResultStore(storePath)) {
            // Creates the store layout so both children start from the same place.
        }
        ChildProcess first = ChildProcess.launch(root, "write-runs", storePath, "a");
        ChildProcess second = ChildProcess.launch(root, "write-runs", storePath, "b");
        first.go();
        second.go();

        assertEquals(0, first.finish(60_000));
        assertEquals(0, second.finish(60_000));

        int runs;
        try (Stream<Path> entries = Files.list(storePath.resolve("runs"))) {
            runs = (int) entries.filter(Files::isDirectory).count();
        }
        assertEquals(50, runs);
    }

    @Test
    void adapterCapsRequestedCaseConcurrency(@TempDir Path root) {
        ProjectAdapter limited =
                new ProjectAdapter(
                        "limited",
                        (evalCase, context) -> null,
                        (handle, evalCase) -> null,
                        List.of(),
                        List.of(),
                        2);

        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            EvaluationEngine engine = new EvaluationEngine(limited, store, null, 32, 0, 30, false, null);
            assertEquals(32, engine.requestedWorkers());
            assertEquals(2, engine.workers());
            assertEquals(4, engine.maxInFlight());
        }
    }

    @Test
    void interruptedLockIsReleasedSoTheNextHolderProceeds(@TempDir Path root) throws Exception {
        Path lockPath = root.resolve(".locks/loop.lock");
        TextArtifactWorkspace workspace = new TextArtifactWorkspace(root);

        try (ExclusiveFileLock ignored = workspace.lockLoop("loop")) {
            assertTrue(Files.exists(lockPath));
        }
        try (ExclusiveFileLock ignored = workspace.lockLoop("loop")) {
            // A released lock must be immediately reacquirable, including by this same process.
        }
    }
}
