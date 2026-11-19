package io.assay.synth;

/**
 * Knobs for one synthesis run.
 *
 * <p>Limits are deliberately explicit: generated cases are cheap to produce and expensive to review,
 * so a run always has a case cap, a model-call cap, and a token cap.
 */
public record SynthesisSettings(
        String scenario,
        String expectedKey,
        int contextsPerDocument,
        int casesPerContext,
        int evolutions,
        long seed,
        Limits limits,
        Quality quality,
        Chunking chunking) {

    /** Hard stops for one run. */
    public record Limits(int maxCases, int maxCalls, int maxTokens) {

        public Limits {
            if (maxCases < 1) {
                throw new IllegalArgumentException("max_cases must be positive");
            }
            if (maxCalls < 1) {
                throw new IllegalArgumentException("max_calls must be positive");
            }
            if (maxTokens < 0) {
                throw new IllegalArgumentException("max_tokens must be non-negative");
            }
        }
    }

    /** Quality filtering and duplicate detection. */
    public record Quality(double threshold, int maxRewriteRetries, double duplicateThreshold) {

        public Quality {
            if (!Double.isFinite(threshold) || threshold < 0 || threshold > 10) {
                throw new IllegalArgumentException("quality_threshold must be between 0 and 10");
            }
            if (maxRewriteRetries < 0) {
                throw new IllegalArgumentException("max_rewrite_retries must be non-negative");
            }
            if (!Double.isFinite(duplicateThreshold) || duplicateThreshold < 0 || duplicateThreshold > 1) {
                throw new IllegalArgumentException("duplicate_threshold must be between 0 and 1");
            }
        }
    }

    /** Chunking for document sources. */
    public record Chunking(int chars, int overlapChars) {

        public Chunking {
            if (chars < 100) {
                throw new IllegalArgumentException("chunk_chars must be at least 100");
            }
            if (overlapChars < 0 || overlapChars >= chars) {
                throw new IllegalArgumentException("chunk_overlap_chars must be between 0 and chunk_chars");
            }
        }
    }

    public SynthesisSettings {
        if (scenario == null || scenario.isBlank()) {
            throw new IllegalArgumentException("scenario must be non-empty");
        }
        if (expectedKey == null || expectedKey.isBlank()) {
            throw new IllegalArgumentException("expected_key must be non-empty");
        }
        if (contextsPerDocument < 1 || casesPerContext < 1) {
            throw new IllegalArgumentException("contexts_per_document and cases_per_context must be positive");
        }
        if (evolutions < 0 || evolutions > 3) {
            throw new IllegalArgumentException("evolutions must be between 0 and 3");
        }
        scenario = scenario.trim();
        expectedKey = expectedKey.trim();
        limits = limits == null ? new Limits(50, 200, 400_000) : limits;
        quality = quality == null ? new Quality(7.0, 2, 0.9) : quality;
        chunking = chunking == null ? new Chunking(1200, 150) : chunking;
    }

    /** Defaults used by the CLI when an option is omitted. */
    public static SynthesisSettings defaults() {
        return new SynthesisSettings(
                "synthetic",
                "answer",
                2,
                2,
                1,
                7L,
                new Limits(50, 200, 400_000),
                new Quality(7.0, 2, 0.9),
                new Chunking(1200, 150));
    }
}
