package io.assay.evolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.EvolutionDiagnosis;
import io.assay.model.TextCandidate;
import io.assay.testing.ScriptedJudge;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

class PromptOptimizerTest {

    private static final EvolutionDiagnosis DIAGNOSIS = new EvolutionDiagnosis(
            "the router ignores refund questions",
            "skill",
            "router",
            List.of("CASE-1"),
            List.of("Router"),
            List.of("keep the JSON shape"),
            Map.of());

    @Test
    void proposesCompleteReplacementsWithOptimizerMetadata() {
        ScriptedJudge judge = new ScriptedJudge(List.of(Map.of("candidates", List.of(
                Map.of("focus", "add refund routing", "content", "route refund questions to REFUND"),
                Map.of("focus", "keep the shape", "content", "route refunds to REFUND and keep JSON")))));
        PromptOptimizer optimizer = PromptOptimizer.gepa(judge);

        List<TextCandidate> candidates = optimizer.generate(DIAGNOSIS, "route everything to GENERAL", 3);

        assertEquals(2, candidates.size());
        TextCandidate first = candidates.get(0);
        assertEquals("po-r3-1", first.candidateId());
        assertEquals("po-3-1", first.candidateVersion());
        assertEquals("gepa", first.metadata().get("optimizer"));
        assertEquals("add refund routing", first.metadata().get("focus"));
        assertEquals(3, first.metadata().get("round"));
        assertEquals("scripted-judge", first.metadata().get("model"));
        assertTrue(judge.prompts().get(0).contains("the router ignores refund questions"));
        assertTrue(judge.prompts().get(0).contains("keep the JSON shape"));
    }

    @Test
    void dropsDuplicatesIdenticalToCurrentAndEmptyCandidates() {
        ScriptedJudge judge = new ScriptedJudge(List.of(Map.of("candidates", List.of(
                Map.of("content", "  route everything to GENERAL  "),
                Map.of("content", ""),
                Map.of("content", "route refund questions to REFUND"),
                Map.of("content", "route refund questions to REFUND")))));
        PromptOptimizer optimizer = PromptOptimizer.copro(judge);

        List<TextCandidate> candidates = optimizer.generate(DIAGNOSIS, "route everything to GENERAL", 1);

        assertEquals(1, candidates.size());
        assertEquals("route refund questions to REFUND", candidates.get(0).content());
        assertEquals("copro", candidates.get(0).metadata().get("optimizer"));
    }

    @Test
    void respectsTheBreadthCap() {
        ScriptedJudge judge = new ScriptedJudge(List.of(Map.of("candidates", List.of(
                Map.of("content", "first candidate text"),
                Map.of("content", "second candidate text"),
                Map.of("content", "third candidate text")))));
        PromptOptimizer optimizer = new PromptOptimizer(judge, PromptOptimizer.Algorithm.COPRO, 2, 0.9);

        assertEquals(2, optimizer.generate(DIAGNOSIS, "baseline artifact", 1).size());
    }

    @Test
    void returnsNothingForAnEmptyOrMalformedResponse() {
        ScriptedJudge empty = new ScriptedJudge(List.of(Map.of()));
        assertEquals(List.of(), PromptOptimizer.gepa(empty).generate(DIAGNOSIS, "baseline", 1));

        ScriptedJudge malformed = new ScriptedJudge(List.of(Map.of("candidates", "not a list")));
        assertEquals(List.of(), PromptOptimizer.gepa(malformed).generate(DIAGNOSIS, "baseline", 1));
    }
}
