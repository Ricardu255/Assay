package io.assay.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.TraceEvent;
import io.assay.store.file.ResultStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuiteRunnerTest {

    @TempDir
    Path temp;

    @Test
    void runsADirectAdapterAndWritesArtifacts() {
        ResultStore store = new ResultStore(temp.resolve("store"));
        RunExecution execution = new SuiteRunner()
                .run(adapter(), cases(), spec("run-1", "run-1", false), RunOptions.defaults(), store, temp.resolve("runs"));
        assertEquals("run-1", execution.runId());
        assertEquals("passed", execution.summary().get("status"));
        assertEquals(1, ((Number) execution.summary().get("case_count")).intValue());
        assertTrue(Files.isRegularFile(execution.reportDir().resolve("report.md")));
        assertEquals("passed", store.getRun("run-1").get("status"));
    }

    @Test
    void loadsTheAdapterFromAClassName() throws Exception {
        Files.writeString(
                temp.resolve("cases.jsonl"),
                "{\"id\":\"case-1\",\"input\":{\"message\":\"hi\"},\"expected\":{},\"scenario\":\"direct\"}\n");
        ResultStore store = new ResultStore(temp.resolve("store"));
        RunExecution execution = new SuiteRunner().run(
                referenceSpec("io.assay.execution.SuiteRunnerTestAdapter", null),
                RunOptions.defaults(),
                store,
                temp.resolve("runs"));
        assertEquals("passed", execution.summary().get("status"));
        assertNotNull(store.getRun(execution.runId()));
    }

    @Test
    void resumesARunWithAnExplicitIdentity() {
        ResultStore store = new ResultStore(temp.resolve("store"));
        SuiteRunner runner = new SuiteRunner();
        RunExecution first = runner.run(
                adapter(), cases(), spec("resume-1", "resume-1", false), RunOptions.defaults(), store, temp.resolve("runs"));
        RunExecution second = runner.run(
                adapter(), cases(), spec("resume-1", "resume-1", true), RunOptions.defaults(), store, temp.resolve("runs"));
        assertEquals(first.runId(), second.runId());
        assertEquals("passed", second.summary().get("status"));
        assertEquals(1, ((Number) second.summary().get("case_count")).intValue());
    }

    @Test
    void rejectsResumeWithoutAnExplicitRunId() {
        assertThrows(IllegalArgumentException.class,
                () -> new RunSpec("adapter", null, "cases.jsonl", "smoke", "online", null, null, true));
    }

    private RunSpec spec(String runId, String identity, boolean resume) {
        return new RunSpec(
                "unused",
                null,
                temp.resolve("cases.jsonl").toString(),
                "smoke",
                "online",
                runId,
                identity,
                resume);
    }

    private RunSpec referenceSpec(String adapterRef, String adapterClass) {
        return new RunSpec(
                adapterRef,
                adapterClass,
                temp.resolve("cases.jsonl").toString(),
                "smoke",
                "online",
                null,
                null,
                false);
    }

    private static List<EvalCase> cases() {
        return List.of(new EvalCase("case-1", Map.of("message", "hi"), Map.of(), "direct"));
    }

    private static ProjectAdapter adapter() {
        return new ProjectAdapter(
                "suite-runner-test",
                (EvalCase evalCase, io.assay.model.RunContext context) -> Map.of("final", "ok"),
                (Object handle, EvalCase evalCase) -> new NormalizedTrace(
                        "trace-" + evalCase.caseId(),
                        "ok",
                        List.of(new TraceEvent("agent", "reply")),
                        Map.of()),
                List.of(),
                List.of());
    }
}
