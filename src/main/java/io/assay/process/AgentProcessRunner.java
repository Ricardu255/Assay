package io.assay.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs an agent command with a deadline, an output budget, and no shell in between.
 *
 * <p>The command is always an explicit argument list, never a string, so shell metacharacters in a case
 * payload can never reach an interpreter. When the deadline or the output budget is exceeded the whole
 * process <em>tree</em> is killed: agent commands routinely start helper servers, and killing only the
 * parent would leak them into later evaluations.
 */
public final class AgentProcessRunner {

    public static final int DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;
    public static final String TRUNCATED_OUTPUT_MARKER = "\n...[output truncated]";

    private AgentProcessRunner() {
    }

    /** A finished process together with its captured output. */
    public record ProcessResult(List<String> command, int returnCode, String stdout, String stderr) {
    }

    public static ProcessResult runAgentProcess(List<String> command, Path cwd) {
        return runAgentProcess(command, cwd, null, 30, Map.of(), DEFAULT_MAX_OUTPUT_BYTES);
    }

    public static ProcessResult runAgentProcess(
            List<String> command,
            Path cwd,
            String inputText,
            double timeoutSeconds,
            Map<String, String> environment,
            int maxOutputBytes) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("agent command must not be empty");
        }
        if (!Files.isDirectory(cwd)) {
            throw new IllegalArgumentException("agent working directory does not exist: " + cwd);
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("agent timeout must be positive");
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("agent output limit must be positive");
        }

        Charset charset = Charset.defaultCharset();
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.directory(cwd.toFile());
        builder.environment().putAll(environment == null ? Map.of() : environment);
        builder.redirectErrorStream(false);
        // Always feed stdin from a file, empty when there is no input: an inheriting child would block
        // on the parent's terminal instead of seeing end-of-input, and an agent command that reads stdin
        // would then hang until the deadline.
        Path stdinFile;
        try {
            stdinFile = Files.createTempFile("assay-stdin", ".tmp");
            Files.write(stdinFile, inputText == null ? new byte[0] : inputText.getBytes(charset));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        builder.redirectInput(stdinFile.toFile());
        try {
            Process process = builder.start();
            AtomicReference<String> reason = new AtomicReference<>();
            AtomicReference<String> limitedStream = new AtomicReference<>();
            Runnable killTree = () -> {
                // Descendants first: once the parent is gone the OS may reparent its children away from us.
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            };

            Captured stdout = new Captured("stdout", maxOutputBytes);
            Captured stderr = new Captured("stderr", maxOutputBytes);
            Thread stdoutReader =
                    stdout.start(process.getInputStream(), reason, limitedStream, killTree);
            Thread stderrReader =
                    stderr.start(process.getErrorStream(), reason, limitedStream, killTree);

            long deadline = System.nanoTime() + (long) (timeoutSeconds * 1_000_000_000L);
            boolean finished;
            try {
                finished = process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                killTree.run();
                throw new IllegalStateException("agent process interrupted", error);
            }
            if (!finished) {
                reason.compareAndSet(null, "timeout");
                killTree.run();
                try {
                    process.waitFor(250, TimeUnit.MILLISECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
            join(stdoutReader, deadline);
            join(stderrReader, deadline);
            stdout.closeQuietly();
            stderr.closeQuietly();

            String stdoutText = stdout.decode(charset);
            String stderrText = stderr.decode(charset);
            if ("output_limit".equals(reason.get())) {
                throw new OutputLimitExceededException(command, limitedStream.get(), stdoutText, stderrText);
            }
            if ("timeout".equals(reason.get())) {
                throw new AgentTimeoutException(command, timeoutSeconds, stdoutText, stderrText);
            }
            return new ProcessResult(List.copyOf(command), process.exitValue(), stdoutText, stderrText);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } finally {
            if (stdinFile != null) {
                try {
                    Files.deleteIfExists(stdinFile);
                } catch (IOException ignored) {
                    // A leftover temp file is harmless; failing the run over it is not.
                }
            }
        }
    }

    private static void join(Thread reader, long deadline) {
        long remaining = Math.max(0, deadline - System.nanoTime());
        try {
            reader.join(remaining / 1_000_000L + 250);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    /** One output stream with a hard byte budget; the first breach stops the whole process. */
    private static final class Captured {

        private final String name;
        private final int limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile boolean truncated;
        private volatile InputStream source;

        private Captured(String name, int limit) {
            this.name = name;
            this.limit = limit;
        }

        private Thread start(
                InputStream stream,
                AtomicReference<String> reason,
                AtomicReference<String> limitedStream,
                Runnable killTree) {
            this.source = stream;
            Thread reader =
                    new Thread(
                            () -> {
                                byte[] chunk = new byte[65536];
                                try {
                                    int read;
                                    while ((read = source.read(chunk)) > 0) {
                                        int remaining = limit - buffer.size();
                                        if (remaining > 0) {
                                            buffer.write(chunk, 0, Math.min(read, remaining));
                                        }
                                        if (read > remaining) {
                                            truncated = true;
                                            reason.set("output_limit");
                                            limitedStream.set(name);
                                            killTree.run();
                                            return;
                                        }
                                    }
                                } catch (IOException error) {
                                    // The stream closes when the process tree is killed; that is the expected path.
                                }
                            },
                            "assay-" + name + "-reader");
            reader.setDaemon(true);
            reader.start();
            return reader;
        }

        private String decode(Charset charset) {
            String value = new String(buffer.toByteArray(), charset);
            return truncated ? value + TRUNCATED_OUTPUT_MARKER : value;
        }

        private void closeQuietly() {
            try {
                if (source != null) {
                    source.close();
                }
            } catch (IOException ignored) {
                // Nothing left to do with a stream we have finished reading.
            }
        }
    }
}
