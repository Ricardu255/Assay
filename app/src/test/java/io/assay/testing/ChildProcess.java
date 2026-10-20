package io.assay.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Launches a second JVM running a fixture main, and watches its marker files. */
public final class ChildProcess {

    private final Process process;
    private final Path start;
    private final Path ready;
    private final Path release;

    private ChildProcess(Process process, Path start, Path ready, Path release) {
        this.process = process;
        this.start = start;
        this.ready = ready;
        this.release = release;
    }

    /** Starts the fixture; it blocks until {@link #go()} is called. */
    public static ChildProcess launch(Path directory, String mode, Path target, String... extra)
            throws IOException {
        Path start = directory.resolve("start-" + mode + "-" + System.nanoTime());
        Path ready = directory.resolve("ready-" + mode + "-" + System.nanoTime());
        Path release = directory.resolve("release-" + mode + "-" + System.nanoTime());

        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(LockHolderMain.class.getName());
        command.add(mode);
        command.add(target.toString());
        command.add(start.toString());
        command.add(ready.toString());
        command.add(release.toString());
        for (String item : extra) {
            command.add(item);
        }
        Process process = new ProcessBuilder(command).inheritIO().start();
        return new ChildProcess(process, start, ready, release);
    }

    public static String javaBinary() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    public static String classPath() {
        return System.getProperty("java.class.path");
    }

    /** Lets the fixture run. */
    public void go() throws IOException {
        Files.writeString(start, "go");
    }

    /** Blocks until the fixture reports it holds the lock. */
    public void awaitReady(long timeoutMillis) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!Files.exists(ready)) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("child never became ready");
            }
            Thread.sleep(10);
        }
    }

    /** Releases the fixture and waits for it to exit. */
    public int finish(long timeoutMillis) throws IOException, InterruptedException {
        Files.writeString(release, "release");
        if (!process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("child did not exit");
        }
        return process.exitValue();
    }

    public void kill() {
        process.destroyForcibly();
    }
}
