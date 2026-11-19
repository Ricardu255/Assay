package io.assay.synth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.metrics.LlmJudge;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

class SynthesizerTest {

    private static final SourceDocument DOCUMENT =
            new SourceDocument("policy.md", "Refunds are accepted within 30 days with a receipt.");

    @Test
    void generatesQualityFilteredEvolvedCasesWithProvenance() {
        ScriptedJudge judge = new ScriptedJudge(List.of(
                Map.of("cases", List.of(
                        Map.of("input", "How long do I have to request a refund?",
                                "expected_output", "Within 30 days with a receipt."),
                        Map.of("input", "What do I need for a refund?",
                                "expected_output", "A receipt."))),
                Map.of("score", 9, "feedback", ""),
                Map.of("input", "How long do I have to request a refund, and under what condition?"),
                Map.of("score", 8, "feedback", ""),
                Map.of("input", "What evidence must accompany a refund request?")));
        Synthesizer synthesizer = new Synthesizer(judge, settings(10, 20, 1, 2));

        SynthesisReport report = synthesizer.synthesize(List.of(DOCUMENT));

        assertEquals(2, report.cases().size());
        assertEquals(0, report.duplicates());
        assertEquals(0, report.discarded());
        assertEquals(5, report.llmCalls());
        assertEquals(50, report.inputTokens());
        assertEquals(25, report.outputTokens());
        assertFalse(report.budgetExhausted());

        SyntheticCase first = report.cases().get(0);
        assertEquals("synth-0001", first.id());
        assertEquals("synthetic", first.metadata().get("source"));
        assertEquals("policy.md", first.metadata().get("source_file"));
        assertEquals(9.0, first.metadata().get("quality"));
        assertEquals(1, ((List<?>) first.metadata().get("evolutions")).size());
        assertEquals("Within 30 days with a receipt.",
                asMap(first.toCaseJson().get("expected")).get("answer"));
    }

    @Test
    void discardsACaseThatStaysBelowTheQualityThreshold() {
        Synthesizer synthesizer = new Synthesizer(new ScriptedJudge(List.of(
                Map.of("cases", List.of(Map.of("input", "vague question", "expected_output", "answer"))),
                Map.of("score", 4, "feedback", "too vague"),
                Map.of("input", "still vague"),
                Map.of("score", 5, "feedback", "still vague"))),
                settings(10, 20, 0, 1));

        SynthesisReport report = synthesizer.synthesize(List.of(DOCUMENT));

        assertEquals(0, report.cases().size());
        assertEquals(1, report.discarded());
        assertEquals(4, report.llmCalls());
    }

    @Test
    void countsNearDuplicatesInsteadOfWritingThem() {
        Synthesizer synthesizer = new Synthesizer(new ScriptedJudge(List.of(
                Map.of("cases", List.of(
                        Map.of("input", "What is the refund window?", "expected_output", "30 days"),
                        Map.of("input", "Which documents are needed?", "expected_output", "A receipt"))),
                Map.of("score", 9, "feedback", ""),
                Map.of("input", "same question"),
                Map.of("score", 9, "feedback", ""),
                Map.of("input", "same question"))),
                settings(10, 20, 1, 2));

        SynthesisReport report = synthesizer.synthesize(List.of(DOCUMENT));

        assertEquals(1, report.cases().size());
        assertEquals(1, report.duplicates());
    }

    @Test
    void stopsWhenTheCallBudgetIsReached() {
        Synthesizer synthesizer = new Synthesizer(new ScriptedJudge(List.of(
                Map.of("cases", List.of(Map.of("input", "question", "expected_output", "answer"))),
                Map.of("score", 9, "feedback", ""))),
                settings(10, 2, 1, 1));

        SynthesisReport report = synthesizer.synthesize(List.of(DOCUMENT));

        assertTrue(report.budgetExhausted());
        assertEquals(2, report.llmCalls());
        assertEquals(1, report.cases().size());
    }

    @Test
    void stopsAtTheCaseCap() {
        Synthesizer synthesizer = new Synthesizer(new ScriptedJudge(List.of(
                Map.of("cases", List.of(
                        Map.of("input", "first", "expected_output", "a"),
                        Map.of("input", "second", "expected_output", "b"))),
                Map.of("score", 9, "feedback", ""),
                Map.of("score", 9, "feedback", ""))),
                settings(1, 20, 0, 2));

        SynthesisReport report = synthesizer.synthesize(List.of(DOCUMENT));

        assertEquals(1, report.cases().size());
    }

    private static SynthesisSettings settings(int maxCases, int maxCalls, int evolutions, int perContext) {
        return new SynthesisSettings(
                "synthetic",
                "answer",
                1,
                perContext,
                evolutions,
                7L,
                new SynthesisSettings.Limits(maxCases, maxCalls, 0),
                new SynthesisSettings.Quality(7.0, 1, 0.9),
                new SynthesisSettings.Chunking(400, 50));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static final class ScriptedJudge implements LlmJudge {

        private final List<Map<String, Object>> responses;
        private int calls;

        private ScriptedJudge(List<Map<String, Object>> responses) {
            this.responses = responses;
        }

        @Override
        public String model() {
            return "scripted";
        }

        @Override
        public Response evaluate(String prompt) {
            Map<String, Object> response = responses.get(Math.min(calls, responses.size() - 1));
            calls++;
            return new Response(response, 10, 5, null, model());
        }
    }
}
