package io.assay.synth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import java.util.List;

class ContextChunkerTest {

    @Test
    void splitsLongDocumentsIntoBoundedOverlappingChunks() {
        String paragraph = "This paragraph explains the refund policy in enough detail. ".repeat(20);
        SourceDocument document = new SourceDocument("policy.md", paragraph + "\n\n" + paragraph);

        List<ContextChunk> chunks = new ContextChunker(300, 50).split(document);

        assertTrue(chunks.size() > 1);
        for (ContextChunk chunk : chunks) {
            assertTrue(chunk.text().length() <= 300, "length=" + chunk.text().length());
            assertEquals("policy.md", chunk.source());
        }
    }

    @Test
    void rejectsImpossibleChunkSettings() {
        assertThrows(IllegalArgumentException.class, () -> new ContextChunker(99, 0));
        assertThrows(IllegalArgumentException.class, () -> new ContextChunker(200, 200));
        assertThrows(IllegalArgumentException.class, () -> new ContextChunker(200, -1));
    }
}
