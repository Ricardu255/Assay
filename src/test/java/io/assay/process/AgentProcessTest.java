package io.assay.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.testing.AgentProcessFixtureMain;
import io.assay.testing.ChildProcess;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deadline and output-budget enforcement, including cleanup of the whole process tree. */
class AgentProcessTest {

    private static List<String> fixture(String... arguments) {
        List<String> command = new java.util.ArrayList<>();
        command.add(ChildProcess.javaBinary());
        command.add("-cp");
        command.add(ChildProcess.classPath());
        command.add(AgentProcessFixtureMain.class.getName());
        command.addAll(List.of(arguments));
        return command;
    }

    @Test
    void capturesOutputFromAnExplicitWorkingDirectory(@TempDir Path directory) {
        AgentProcessRunner.ProcessResult result =
                AgentProcessRunner.runAgentProcess(
                        fixture("cwd"), directory, null, 30, Map.of(), AgentProcessRunner.DEFAULT_MAX_OUTPUT_BYTES);

        assertEquals(0, result.returnCode());
        assertEquals(directory.getFileName().toString(), result.stdout().strip());
    }

    @Test
    void terminatesATimedOutAgentProcess(@TempDir Path directory) {
        AgentTimeoutException raised =
                assertThrows(
                        AgentTimeoutException.class,
                        () ->
                                AgentProcessRunner.runAgentProcess(
                                        fixture("sleep", "5000"),
                                        directory,
                                        null,
                                        1,
                                        Map.of(),
                                        AgentProcessRunner.DEFAULT_MAX_OUTPUT_BYTES));

        assertTrue(raised.stdout().contains("started"), raised.stdout());
    }

    @Test
    void limitsCapturedStdout(@TempDir Path directory) {
        OutputLimitExceededException raised =
                assertThrows(
                        OutputLimitExceededException.class,
                        () ->
                                AgentProcessRunner.runAgentProcess(
                                        fixture("bigout", "5000"), directory, null, 30, Map.of(), 16));

        assertEquals("stdout", raised.stream());
        assertEquals("o".repeat(16) + AgentProcessRunner.TRUNCATED_OUTPUT_MARKER, raised.stdout());
    }

    /** Killing only the parent would leave the child running into later evaluations. */
    @Test
    void timeoutTerminatesTheWholeProcessTree(@TempDir Path directory) throws Exception {
        long started = System.nanoTime();
        AgentTimeoutException raised =
                assertThrows(
                        AgentTimeoutException.class,
                        () ->
                                AgentProcessRunner.runAgentProcess(
                                        fixture("spawn", "4000"),
                                        directory,
                                        null,
                                        1,
                                        Map.of(),
                                        AgentProcessRunner.DEFAULT_MAX_OUTPUT_BYTES));
        double elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0;

        assertTrue(elapsedSeconds < 3, "the runner must not wait for the spawned child: " + elapsedSeconds);
        long childPid = Long.parseLong(raised.stdout().strip());
        long deadline = System.currentTimeMillis() + 5_000;
        while (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                "the grandchild process must be gone after the tree is killed");
    }

    @Test
    void rejectsInvalidConfiguration(@TempDir Path directory) {
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentProcessRunner.runAgentProcess(List.of(), directory));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentProcessRunner.runAgentProcess(fixture("cwd"), directory.resolve("absent")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentProcessRunner.runAgentProcess(fixture("cwd"), directory, null, 0, Map.of(), 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentProcessRunner.runAgentProcess(fixture("cwd"), directory, null, 1, Map.of(), 0));
    }

    @Test
    void passesInputThroughStandardInput(@TempDir Path directory) {
        AgentProcessRunner.ProcessResult result =
                AgentProcessRunner.runAgentProcess(
                        fixture("cwd"), directory, "ignored", 30, Map.of(), AgentProcessRunner.DEFAULT_MAX_OUTPUT_BYTES);

        assertEquals(0, result.returnCode());
    }
}
