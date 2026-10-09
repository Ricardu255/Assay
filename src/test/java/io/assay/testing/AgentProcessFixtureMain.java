package io.assay.testing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The child programs the process-runner tests execute.
 *
 * <p>The process-runner tests need child programs that print, stall, flood stdout, or spawn a grandchild.
 * A JVM has no inline script equivalent, so those behaviours live in this one fixture class that the
 * tests launch as a separate process.
 */
public final class AgentProcessFixtureMain {

    private AgentProcessFixtureMain() {
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "cwd" -> System.out.println(Path.of("").toAbsolutePath().getFileName());
            case "sleep" -> {
                System.out.println("started");
                System.out.flush();
                Thread.sleep(Long.parseLong(args[1]));
            }
            case "bigout" -> {
                System.out.println("o".repeat(100));
                System.out.flush();
                Thread.sleep(Long.parseLong(args[1]));
            }
            case "spawn" -> {
                ProcessHandle child = spawnSleeper(Long.parseLong(args[1]));
                System.out.println(child.pid());
                System.out.flush();
                Thread.sleep(10_000);
            }
            default -> throw new IllegalArgumentException("unknown mode: " + args[0]);
        }
    }

    private static ProcessHandle spawnSleeper(long millis) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(ChildProcess.javaBinary());
        command.add("-cp");
        command.add(ChildProcess.classPath());
        command.add(AgentProcessFixtureMain.class.getName());
        command.add("sleep");
        command.add(String.valueOf(millis));
        return new ProcessBuilder(command).start().toHandle();
    }
}
