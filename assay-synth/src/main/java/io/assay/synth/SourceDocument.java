package io.assay.synth;

/** One text source a synthetic case can be grounded in. */
public record SourceDocument(String name, String text) {

    public SourceDocument {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("source document requires a name");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("source document requires text: " + name);
        }
    }
}
