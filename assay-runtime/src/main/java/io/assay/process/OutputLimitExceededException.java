package io.assay.process;

import java.util.List;

/**
 * Raised when an agent process had to be killed because it outran its output budget.
 *
 * <p>Both streams are attached even though the process was killed, because the bytes it did produce are
 * usually the only clue about what it was doing.
 */
public class OutputLimitExceededException extends RuntimeException {

    private final List<String> command;
    private final String stream;
    private final String stdout;
    private final String stderr;

    public OutputLimitExceededException(List<String> command, String stream, String stdout, String stderr) {
        super("agent " + stream + " exceeded the configured output limit");
        this.command = List.copyOf(command);
        this.stream = stream;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    public List<String> command() {
        return command;
    }

    public String stream() {
        return stream;
    }

    public String stdout() {
        return stdout;
    }

    public String stderr() {
        return stderr;
    }
}
