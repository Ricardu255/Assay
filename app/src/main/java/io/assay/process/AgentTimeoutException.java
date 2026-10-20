package io.assay.process;

import java.util.List;

/**
 * Raised when an agent process exceeded its deadline.
 *
 * <p>Carries whatever the process managed to write before it was killed.
 */
public class AgentTimeoutException extends RuntimeException {

    private final List<String> command;
    private final double timeoutSeconds;
    private final String stdout;
    private final String stderr;

    public AgentTimeoutException(List<String> command, double timeoutSeconds, String stdout, String stderr) {
        super("agent process timed out after " + timeoutSeconds + " seconds");
        this.command = List.copyOf(command);
        this.timeoutSeconds = timeoutSeconds;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    public List<String> command() {
        return command;
    }

    public double timeoutSeconds() {
        return timeoutSeconds;
    }

    public String stdout() {
        return stdout;
    }

    public String stderr() {
        return stderr;
    }
}
