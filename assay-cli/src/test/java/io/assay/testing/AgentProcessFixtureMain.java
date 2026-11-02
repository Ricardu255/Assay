package io.assay.testing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The child programs the process-runner tests execute.
 *
 * <p>The process-runner tests need child programs that print, stall, flood stdout, or spawn a grandchild.
 * A JVM has no inline script equivalent, so those behaviours live in this one fixture class. The
 * grandchild is a native long-lived command rather than a nested JVM, keeping the timeout test
 * independent of second-JVM startup speed.
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
        long seconds = Math.max(1, (millis + 999) / 1000);
        List<String> command = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            // A nested JVM takes seconds to start on some Windows hosts; the test only needs a
            // long-lived grandchild, so use a native command that starts immediately.
            command.add("cmd.exe");
            command.add("/c");
            command.add("ping");
            command.add("-n");
            command.add(String.valueOf(seconds + 1));
            command.add("127.0.0.1");
        } else {
            command.add("sleep");
            command.add(String.valueOf(seconds));
        }
        return new ProcessBuilder(command).start().toHandle();
    }
}
