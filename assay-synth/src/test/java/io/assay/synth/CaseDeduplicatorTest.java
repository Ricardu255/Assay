package io.assay.synth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CaseDeduplicatorTest {

    @Test
    void rejectsAnExactDuplicateAndNearDuplicates() {
        CaseDeduplicator deduplicator = new CaseDeduplicator(0.7);
        String question = "Which documents do I need to submit for a refund under the policy";

        assertFalse(deduplicator.isDuplicate(question));
        assertTrue(deduplicator.isDuplicate(question));
        assertTrue(deduplicator.isDuplicate(question + " today"));
    }

    @Test
    void acceptsADistinctQuestion() {
        CaseDeduplicator deduplicator = new CaseDeduplicator(0.8);

        assertFalse(deduplicator.isDuplicate("How long does a refund take to arrive"));
        assertFalse(deduplicator.isDuplicate("Which tools can the support agent call for escalation"));
    }
}
