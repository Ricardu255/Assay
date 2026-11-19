package io.assay.synth;

/** A bounded slice of a source document used as one synthesis context. */
public record ContextChunk(String source, String text) {

    public ContextChunk {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("context chunk requires text");
        }
    }
}
