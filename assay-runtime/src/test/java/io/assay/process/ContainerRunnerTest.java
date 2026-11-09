package io.assay.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The container command is the security boundary, so its contents are asserted directly. */
class ContainerRunnerTest {

    @Test
    void buildsRestrictedContainerCommandByDefault(@TempDir Path directory) {
        List<String> command = ContainerRunner.buildContainerCommand("python:3.12", List.of("python", "agent.py"), directory);

        assertEquals(List.of("docker", "run", "--rm"), command.subList(0, 3));
        assertTrue(command.contains("none"));
        assertTrue(command.contains("ALL"));
        assertTrue(command.contains("no-new-privileges"));
        assertTrue(command.stream().anyMatch(item -> item.endsWith(",readonly")), command.toString());
        assertEquals(
                List.of("python:3.12", "python", "agent.py"),
                command.subList(command.size() - 3, command.size()));
    }

    @Test
    void writableWorkspaceAndNetworkAreOptIn(@TempDir Path directory) {
        ContainerRunner.Options options = new ContainerRunner.Options("podman", "1g", 2, 64, true, true);
        List<String> command =
                ContainerRunner.buildContainerCommand("python:3.12", List.of("python", "agent.py"), directory, options);

        assertEquals("podman", command.get(0));
        assertTrue(command.contains("bridge"));
        assertTrue(command.stream().noneMatch(item -> item.endsWith(",readonly")));
        assertTrue(command.contains("1g"));
        assertTrue(command.contains("64"));
    }

    /** The assembled argv, the workspace, and the deadline must all reach the process runner. */
    @Test
    void delegatesTimeoutAndWorkspaceToProcessRunner(@TempDir Path directory) {
        AtomicReference<List<String>> capturedCommand = new AtomicReference<>();
        AtomicReference<Path> capturedCwd = new AtomicReference<>();
        AtomicReference<Double> capturedTimeout = new AtomicReference<>();

        ContainerRunner.ProcessExecutor fake =
                (command, cwd, inputText, timeoutSeconds, environment, maxOutputBytes) -> {
                    capturedCommand.set(command);
                    capturedCwd.set(cwd);
                    capturedTimeout.set(timeoutSeconds);
                    return new AgentProcessRunner.ProcessResult(command, 0, "ok", "");
                };

        AgentProcessRunner.ProcessResult result =
                ContainerRunner.runAgentContainer(
                        "python:3.12",
                        List.of("python", "agent.py"),
                        directory,
                        ContainerRunner.Options.defaults(),
                        12,
                        fake);

        assertEquals("ok", result.stdout());
        assertEquals(directory, capturedCwd.get());
        assertEquals(12.0, capturedTimeout.get(), 1e-9);
        assertEquals(
                ContainerRunner.buildContainerCommand("python:3.12", List.of("python", "agent.py"), directory),
                capturedCommand.get());
    }

    @Test
    void rejectsAnInvalidConfiguration(@TempDir Path directory) {
        assertThrows(
                IllegalArgumentException.class,
                () -> ContainerRunner.buildContainerCommand("", List.of("python"), directory));
        assertThrows(
                IllegalArgumentException.class,
                () -> ContainerRunner.buildContainerCommand("python:3.12", List.of(), directory));
        assertThrows(
                IllegalArgumentException.class,
                () -> ContainerRunner.buildContainerCommand("python:3.12", List.of("python"), directory.resolve("absent")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ContainerRunner.Options("docker", "512m", 0, 128, false, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ContainerRunner.Options("docker", "512m", 1, 0, false, false));
    }

    @Test
    void mountsTheWorkspaceReadOnlyByDefault(@TempDir Path directory) {
        List<String> command = ContainerRunner.buildContainerCommand("python:3.12", List.of("python"), directory);
        String mount = command.get(command.indexOf("--mount") + 1);

        assertEquals(
                "type=bind,src=" + directory.toAbsolutePath().normalize() + ",dst=/workspace,readonly", mount);
    }

    @Test
    void runAgentContainerRejectsAMissingWorkspace() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ContainerRunner.runAgentContainer(
                                "python:3.12",
                                List.of("python"),
                                Path.of("no-such-workspace"),
                                ContainerRunner.Options.defaults(),
                                5,
                                (command, cwd, input, timeout, environment, maxOutputBytes) ->
                                        new AgentProcessRunner.ProcessResult(command, 0, "", "")));
    }

    @Test
    void emptyEnvironmentIsPassedThrough(@TempDir Path directory) {
        AtomicReference<Map<String, String>> environment = new AtomicReference<>();
        ContainerRunner.runAgentContainer(
                "python:3.12",
                List.of("python"),
                directory,
                ContainerRunner.Options.defaults(),
                5,
                (command, cwd, input, timeout, env, maxOutputBytes) -> {
                    environment.set(env);
                    return new AgentProcessRunner.ProcessResult(command, 0, "", "");
                });

        assertTrue(environment.get().isEmpty());
    }
}
