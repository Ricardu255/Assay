package io.assay.synth;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a document into overlapping character windows aligned to paragraph boundaries where possible.
 *
 * <p>Character windows are deliberate: a token-exact splitter would pull a tokenizer dependency into
 * the module, and synthesis quality does not need exact token counts.
 */
public final class ContextChunker {

    private final int chunkChars;
    private final int overlapChars;

    public ContextChunker(int chunkChars, int overlapChars) {
        if (chunkChars < 100) {
            throw new IllegalArgumentException("chunk_chars must be at least 100");
        }
        if (overlapChars < 0 || overlapChars >= chunkChars) {
            throw new IllegalArgumentException("chunk_overlap_chars must be non-negative and below chunk_chars");
        }
        this.chunkChars = chunkChars;
        this.overlapChars = overlapChars;
    }

    /** Splits one document; blank input produces no chunks. */
    public List<ContextChunk> split(SourceDocument document) {
        String text = document.text().replace("\r\n", "\n").trim();
        List<ContextChunk> chunks = new ArrayList<>();
        if (text.isEmpty()) {
            return chunks;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkChars);
            if (end < text.length()) {
                int boundary = text.lastIndexOf("\n\n", end);
                if (boundary > start + chunkChars / 2) {
                    end = boundary;
                }
            }
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(new ContextChunk(document.name(), piece));
            }
            if (end >= text.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlapChars);
        }
        return chunks;
    }
}
