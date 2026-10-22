package io.assay.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.CheckResult;
import io.assay.model.EvalCase;
import io.assay.model.NormalizedTrace;
import io.assay.model.Rule;
import io.assay.model.TraceEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SequenceRulesTest {

    @Test
    void exactSequencePassesAndReportsKeys() {
        Rule rule = Rule.ofValue(
                "tool_chain", "events", "sequence_eq",
                List.of("Router/route", "Tool/search", "Tool/answer"));
        CheckResult check = evaluate(rule, trace("Router/route", "Tool/search", "Tool/answer"));

        assertTrue(check.passed());
        assertEquals(List.of("Router/route", "Tool/search", "Tool/answer"), check.actual());
        assertEquals(List.of("Router/route", "Tool/search", "Tool/answer"), check.expected());
    }

    @Test
    void orderedSubsequenceAllowsExtraSteps() {
        Rule rule = Rule.ofValue(
                "tool_chain", "events", "sequence_contains",
                List.of("Router/route", "Tool/search", "Tool/answer"));
        CheckResult check = evaluate(rule, trace("Router/route", "Tool/search", "Log/write", "Tool/answer"));

        assertTrue(check.passed());
    }

    @Test
    void missingOrderedStepNamesTheFirstGap() {
        Rule rule = Rule.ofValue(
                "tool_chain", "events", "sequence_contains",
                List.of("Router/route", "Tool/search", "Tool/answer"));
        CheckResult check = evaluate(rule, trace("Router/route", "Tool/search"));

        assertFalse(check.passed());
        assertTrue(check.message().contains("missing ordered step: Tool/answer"), check.message());
    }

    @Test
    void exactSequenceReportsTheFirstMismatch() {
        Rule rule = Rule.ofValue(
                "tool_chain", "events", "sequence_eq",
                List.of("Router/route", "Tool/search", "Tool/answer"));
        CheckResult check = evaluate(rule, trace("Router/route", "Tool/answer", "Tool/search"));

        assertFalse(check.passed());
        assertTrue(check.message().contains("step 2 expected Tool/search but was Tool/answer"), check.message());
    }

    @Test
    void toolArgumentsCanBeMatched() {
        Rule rule = Rule.ofValue(
                "search_args", "events", "sequence_contains",
                List.of(Map.of("step", "Tool/search", "args", Map.of("query", "refund"))));
        CheckResult check = evaluate(rule, traceWithFields(
                new TraceEvent("Tool", "search", "ok", 0, null, Map.of("query", "refund"))));

        assertTrue(check.passed());
    }

    @Test
    void toolArgumentMismatchIsReported() {
        Rule rule = Rule.ofValue(
                "search_args", "events", "sequence_eq",
                List.of(Map.of("step", "Tool/search", "args", Map.of("query", "refund"))));
        CheckResult check = evaluate(rule, traceWithFields(
                new TraceEvent("Tool", "search", "ok", 0, null, Map.of("query", "other"))));

        assertFalse(check.passed());
        assertTrue(check.message().contains("args mismatch"), check.message());
    }

    @Test
    void nestedSequenceMatchesAContiguousBlock() {
        Rule rule = Rule.ofValue(
                "retry_block", "events", "sequence_contains",
                List.of("Router/route", List.of("Tool/search", "Tool/answer"), "Log/write"));
        CheckResult check = evaluate(rule, trace(
                "Router/route", "Tool/noise", "Tool/search", "Tool/answer", "Log/write"));

        assertTrue(check.passed());
    }

    @Test
    void nestedSequenceRequiresItsOwnStepsToBeContiguous() {
        Rule rule = Rule.ofValue(
                "retry_block", "events", "sequence_contains",
                List.of("Router/route", List.of("Tool/search", "Tool/answer"), "Log/write"));
        CheckResult check = evaluate(rule, trace(
                "Router/route", "Tool/search", "Tool/noise", "Tool/answer", "Log/write"));

        assertFalse(check.passed());
        assertTrue(check.message().contains("missing ordered group"), check.message());
    }

    @Test
    void expectedSequenceCanComeFromTheCase() {
        Rule rule = Rule.of("tool_chain", "events", "sequence_eq", "tool_chain");
        EvalCase evalCase = new EvalCase(
                "C1", Map.of(), Map.of("tool_chain", List.of("Router/route", "Tool/answer")), "default");
        CheckResult check = Rules.evaluateRule(rule, "hard", evalCase, trace("Router/route", "Tool/answer"));

        assertTrue(check.passed());
    }

    @Test
    void nonListActualFailsWithoutThrowing() {
        Rule rule = Rule.ofValue(
                "tool_chain", "final_output", "sequence_eq", List.of("Router/route"));
        CheckResult check = evaluate(rule, trace("Router/route"));

        assertFalse(check.passed());
        assertTrue(check.message().contains("expected a sequence"), check.message());
    }

    private static CheckResult evaluate(Rule rule, NormalizedTrace trace) {
        return Rules.evaluateRule(
                rule, "hard", new EvalCase("C1", Map.of(), Map.of(), "default"), trace);
    }

    private static NormalizedTrace traceWithFields(TraceEvent... events) {
        return new NormalizedTrace("trace-1", "ok", List.of(events), Map.of());
    }

    private static NormalizedTrace trace(String... steps) {
        List<TraceEvent> events = java.util.Arrays.stream(steps)
                .map(step -> {
                    String[] parts = step.split("/", 2);
                    return new TraceEvent(parts[0], parts[1]);
                })
                .toList();
        return new NormalizedTrace("trace-1", "ok", events, Map.of());
    }
}
