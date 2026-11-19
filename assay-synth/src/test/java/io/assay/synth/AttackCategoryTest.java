package io.assay.synth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import java.util.List;

class AttackCategoryTest {

    @Test
    void blankSelectsEveryCategory() {
        assertEquals(AttackCategory.values().length, AttackCategory.parse(null).size());
        assertEquals(AttackCategory.values().length, AttackCategory.parse("  ").size());
    }

    @Test
    void parsesCommaSeparatedNamesWithDashes() {
        List<AttackCategory> categories = AttackCategory.parse("prompt-injection, JAILBREAK");

        assertEquals(List.of(AttackCategory.PROMPT_INJECTION, AttackCategory.JAILBREAK), categories);
    }

    @Test
    void rejectsUnknownAndEmptySelections() {
        assertThrows(IllegalArgumentException.class, () -> AttackCategory.parse("prompt_injection,nope"));
        assertThrows(IllegalArgumentException.class, () -> AttackCategory.parse(" , "));
    }

    @Test
    void severitiesAreOrderedAndBounded() {
        for (AttackCategory category : AttackCategory.values()) {
            assertTrue(category.severity() >= 1 && category.severity() <= 3, category.name());
            assertTrue(category.instruction().length() > 10, category.name());
        }
        assertEquals(3, AttackCategory.TOOL_MISUSE.severity());
        assertEquals(1, AttackCategory.OVER_REFUSAL.severity());
    }
}
