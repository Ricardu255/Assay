package io.assay.store;

import static org.junit.jupiter.api.Assertions.*;

import io.assay.json.Json;
import io.assay.model.CaseResult;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreRecoveryTest {

    private static CaseResult result(String id) {
        return new CaseResult("run", "smoke", "online", new EvalCase(id, Map.of(), Map.of(), "default"),
                new NormalizedTrace("trace-" + id, "answer", List.of(), Map.of()), List.of());
    }

    private static Path initialize(Path root) {
        try (ResultStore store = new ResultStore(root); ResultStore.Handle lock = store.lockRun("run")) {
            store.startRun("run", "adapter", "smoke", "online", Map.of());
            store.saveCase(result("A"));
        }
        return root.resolve("runs/run/cases.jsonl");
    }

    @Test
    void truncationThenAnotherInterruptionPreservesNewCommittedResults(@TempDir Path root) throws Exception {
        Path log = initialize(root);
        Files.writeString(log, "{\"case\":", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        try (ResultStore store = new ResultStore(root); ResultStore.Handle lock = store.lockRun("run")) {
            store.startRun("run", "adapter", "smoke", "online", Map.of(), true, null);
            store.saveCase(result("B"));
            assertEquals(2, store.listResults("run").size());
            // No finishRun: simulates another process interruption before compaction.
        }
        try (ResultStore store = new ResultStore(root)) {
            assertTrue(store.hasCase("run", "A"));
            assertTrue(store.hasCase("run", "B"));
            assertEquals(2, store.listResults("run").size());
        }
    }

    @Test
    void repairsAnIncompleteUtf8CharacterAtTheTail(@TempDir Path root) throws Exception {
        Path log = initialize(root);
        byte[] tail = "{\"answer\":\"中".getBytes(StandardCharsets.UTF_8);
        Files.write(log, Arrays.copyOf(tail, tail.length - 1), StandardOpenOption.APPEND);
        try (ResultStore store = new ResultStore(root); ResultStore.Handle lock = store.lockRun("run")) {
            store.startRun("run", "adapter", "smoke", "online", Map.of(), true, null);
            store.saveCase(result("B"));
        }
        try (ResultStore store = new ResultStore(root)) {
            assertEquals(2, store.listResults("run").size());
        }
    }

    @Test
    void insertsASeparatorAfterACompleteRecordWithoutNewline(@TempDir Path root) throws Exception {
        Path log = initialize(root);
        byte[] content = Files.readAllBytes(log);
        Files.write(log, Arrays.copyOf(content, content.length - 1));
        try (ResultStore store = new ResultStore(root); ResultStore.Handle lock = store.lockRun("run")) {
            store.startRun("run", "adapter", "smoke", "online", Map.of(), true, null);
            store.saveCase(result("B"));
        }
        try (ResultStore store = new ResultStore(root)) {
            assertEquals(2, store.listResults("run").size());
        }
    }

    @Test
    void rejectsCorruptionInTheMiddleInsteadOfSilentlyDroppingLaterRecords(@TempDir Path root) throws Exception {
        Path log = initialize(root);
        Files.writeString(log, "{broken}\n" + Json.write(result("B")) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        byte[] before = Files.readAllBytes(log);
        try (ResultStore store = new ResultStore(root); ResultStore.Handle lock = store.lockRun("run")) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> store.startRun("run", "adapter", "smoke", "online", Map.of(), true, null));
            assertTrue(error.getMessage().contains("corrupt case log"));
        }
        assertArrayEquals(before, Files.readAllBytes(log));
    }

    @Test
    void failedAppendDoesNotPublishUncommittedCacheEntries(@TempDir Path root) throws Exception {
        try (ResultStore store = new ResultStore(root); ResultStore.Handle lock = store.lockRun("run")) {
            store.startRun("run", "adapter", "smoke", "online", Map.of());
            assertTrue(store.listResults("run").isEmpty());
            Files.createDirectory(root.resolve("runs/run/cases.jsonl"));
            assertThrows(UncheckedIOException.class, () -> store.saveCase(result("A")));
            assertFalse(store.hasCase("run", "A"));
        }
    }
}
