package io.assay.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds and runs a container-isolated agent command.
 *
 * <p>The defaults are deliberately restrictive — no network, a read-only root filesystem, a read-only
 * workspace mount, all capabilities dropped, and CPU/memory/PID caps — so a candidate artifact cannot
 * reach the host or a later evaluation.
 */
public final class ContainerRunner {

    private ContainerRunner() {
    }

    /** The container sandbox configuration. */
    public record Options(
            String engine, String memory, double cpus, int pidsLimit, boolean networkEnabled, boolean workspaceWritable) {

        public static Options defaults() {
            return new Options("docker", "512m", 1, 128, false, false);
        }

        public Options {
            if (cpus <= 0 || pidsLimit < 1) {
                throw new IllegalArgumentException("container CPU and PID limits must be positive");
            }
        }
    }

    /** The seam that runs the assembled command; lets a caller swap in its own sandbox. */
    @FunctionalInterface
    public interface ProcessExecutor {
        AgentProcessRunner.ProcessResult run(
                List<String> command,
                Path cwd,
                String inputText,
                double timeoutSeconds,
                Map<String, String> environment,
                int maxOutputBytes);
    }

    public static List<String> buildContainerCommand(String image, List<String> command, Path workspace) {
        return buildContainerCommand(image, command, workspace, Options.defaults());
    }

    public static List<String> buildContainerCommand(
            String image, List<String> command, Path workspace, Options options) {
        if (image == null || image.isBlank() || command == null || command.isEmpty()) {
            throw new IllegalArgumentException("container image and command are required");
        }
        if (!Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("container workspace does not exist: " + workspace);
        }

        String mount = "type=bind,src=" + workspace.toAbsolutePath().normalize() + ",dst=/workspace";
        if (!options.workspaceWritable()) {
            mount += ",readonly";
        }
        List<String> argv = new ArrayList<>();
        argv.add(options.engine());
        argv.add("run");
        argv.add("--rm");
        argv.add("--network");
        argv.add(options.networkEnabled() ? "bridge" : "none");
        argv.add("--read-only");
        argv.add("--cap-drop");
        argv.add("ALL");
        argv.add("--security-opt");
        argv.add("no-new-privileges");
        argv.add("--pids-limit");
        argv.add(String.valueOf(options.pidsLimit()));
        argv.add("--memory");
        argv.add(options.memory());
        argv.add("--cpus");
        argv.add(String.valueOf(options.cpus()));
        argv.add("--tmpfs");
        argv.add("/tmp:rw,noexec,nosuid,size=64m");
        argv.add("--mount");
        argv.add(mount);
        argv.add("--workdir");
        argv.add("/workspace");
        argv.add(image);
        argv.addAll(command);
        return argv;
    }

    public static AgentProcessRunner.ProcessResult runAgentContainer(
            String image, List<String> command, Path workspace, double timeoutSeconds) {
        return runAgentContainer(image, command, workspace, Options.defaults(), timeoutSeconds);
    }

    public static AgentProcessRunner.ProcessResult runAgentContainer(
            String image, List<String> command, Path workspace, Options options, double timeoutSeconds) {
        return runAgentContainer(image, command, workspace, options, timeoutSeconds, AgentProcessRunner::runAgentProcess);
    }

    public static AgentProcessRunner.ProcessResult runAgentContainer(
            String image,
            List<String> command,
            Path workspace,
            Options options,
            double timeoutSeconds,
            ProcessExecutor executor) {
        List<String> argv = buildContainerCommand(image, command, workspace, options);
        return executor.run(
                argv,
                workspace,
                null,
                timeoutSeconds,
                Map.of(),
                AgentProcessRunner.DEFAULT_MAX_OUTPUT_BYTES);
    }
}
