package io.assay.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Everything the automatic evolution loop needs from a domain project.
 *
 * <p>A baseline artifact plus three hooks: diagnose, generate candidates, and build a run adapter for a
 * staged artifact.
 */
public record AutoEvolutionAdapter(
        String name,
        Path baselineArtifact,
        String baselineVersion,
        String targetType,
        String targetId,
        Diagnoser diagnose,
        CandidateGenerator generateCandidates,
        AdapterBuilder buildAdapter) {

    /** Turns a failing run summary into an evidence-bound diagnosis. */
    @FunctionalInterface
    public interface Diagnoser {
        EvolutionDiagnosis diagnose(Map<String, Object> summary);
    }

    /** Proposes bounded text candidates for the current artifact content. */
    @FunctionalInterface
    public interface CandidateGenerator {
        List<TextCandidate> generate(EvolutionDiagnosis diagnosis, Object currentContent, int roundNumber);
    }

    /** Builds the evaluation adapter that runs a staged artifact at a given version. */
    @FunctionalInterface
    public interface AdapterBuilder {
        ProjectAdapter build(Path artifact, String version);
    }
}
