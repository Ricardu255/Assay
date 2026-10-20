package io.assay.testing;

import io.assay.store.ResultStore;
import io.assay.workspace.TextArtifactWorkspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A tiny program the concurrency tests launch in a second JVM.
 *
 * <p>File locks only prove anything across processes, so the contention tests need a real second
 * process. Coordination uses marker files rather than anything clever: the parent creates
 * {@code start}, the child signals {@code ready} once it holds the lock, and waits for {@code release}.
 */
public final class LockHolderMain {

    private LockHolderMain() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path target = Path.of(args[1]);
        Path startFile = Path.of(args[2]);
        Path readyFile = Path.of(args[3]);
        Path releaseFile = Path.of(args[4]);
        String extra = args.length > 5 ? args[5] : "";

        await(startFile);
        switch (mode) {
            case "workspace-lock" -> {
                try (AutoCloseable lock = new TextArtifactWorkspace(target).lockLoop("cross-process-loop")) {
                    Files.writeString(readyFile, "ready");
                    await(releaseFile);
                }
            }
            case "run-lock" -> {
                try (ResultStore store = new ResultStore(target);
                        ResultStore.Handle lock = store.lockRun("shared-run")) {
                    Files.writeString(readyFile, "ready");
                    await(releaseFile);
                }
            }
            case "write-runs" -> {
                try (ResultStore store = new ResultStore(target)) {
                    for (int index = 0; index < 25; index++) {
                        String runId = extra + "-" + index;
                        store.startRun(runId, "adapter", "suite", "online", Map.of());
                        store.finishRun(runId, "passed");
                    }
                }
            }
            default -> throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    private static void await(Path marker) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!Files.exists(marker)) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("timed out waiting for " + marker);
            }
            Thread.sleep(10);
        }
    }
}
