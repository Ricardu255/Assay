package io.assay.execution;

import java.nio.file.Path;
import java.util.Map;

/** The outcome of one executed run: its id, summary, and report directory. */
public record RunExecution(String runId, Map<String, Object> summary, Path reportDir) {
}
