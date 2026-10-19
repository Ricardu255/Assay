package io.assay.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.assay.model.Rule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The rule array shared by the HTTP and trace-file adapters rejects malformed entries. */
class RuleConfigTest {

    @Test
    void parsesTheSupportedRuleShapes() {
        List<Rule> rules = RuleConfig.parse(
                List.of(
                        Map.of("name", "route", "actual", "fields.route", "operator", "eq", "expected", "route"),
                        Map.of("name", "present", "actual", "final_output", "operator", "exists"),
                        Map.of(
                                "name", "length",
                                "actual", "final_output",
                                "operator", "min",
                                "value", 2,
                                "suspected_modules", List.of("Planner"))),
                "hard_gates");

        assertEquals(3, rules.size());
        assertEquals("route", rules.get(0).name());
        assertEquals("exists", rules.get(1).operator());
        assertEquals(List.of("Planner"), rules.get(2).suspectedModules());
    }

    @Test
    void rejectsMalformedRulesWithTheAdapterMessages() {
        assertThrows(IllegalArgumentException.class, () -> RuleConfig.parse(Map.of(), "hard_gates"));
        assertThrows(IllegalArgumentException.class, () -> RuleConfig.parse(
                List.of(Map.of("name", "x", "actual", "a", "expected", "b", "typo", true)), "hard_gates"));
        assertThrows(IllegalArgumentException.class, () -> RuleConfig.parse(
                List.of(
                        Map.of("name", "same", "actual", "a", "expected", "b"),
                        Map.of("name", "same", "actual", "c", "expected", "d")),
                "hard_gates"));
        assertThrows(IllegalArgumentException.class, () -> RuleConfig.parse(
                List.of(Map.of("name", "x", "actual", "a", "operator", "exists", "value", 1)), "hard_gates"));
        assertThrows(IllegalArgumentException.class, () -> RuleConfig.parse(
                List.of(Map.of("name", "x", "actual", "a", "expected", "b", "suspected_modules", "Planner")),
                "hard_gates"));
        assertThrows(IllegalArgumentException.class, () -> RuleConfig.parse(
                List.of(Map.of("name", "x", "actual", "", "expected", "b")), "hard_gates"));
    }
}
