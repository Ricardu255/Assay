package io.assay.testing;

import io.assay.metrics.LlmJudge;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A deterministic judge that replays canned JSON responses and records the prompts. */
public final class ScriptedJudge implements LlmJudge {

    private final List<Map<String, Object>> responses;
    private final List<String> prompts = new ArrayList<>();
    private int calls;

    public ScriptedJudge(List<Map<String, Object>> responses) {
        if (responses == null || responses.isEmpty()) {
            throw new IllegalArgumentException("scripted judge requires at least one response");
        }
        this.responses = List.copyOf(responses);
    }

    @Override
    public String model() {
        return "scripted-judge";
    }

    @Override
    public Response evaluate(String prompt) {
        prompts.add(prompt);
        Map<String, Object> response = responses.get(Math.min(calls, responses.size() - 1));
        calls++;
        return new Response(response, 10, 5, new BigDecimal("0.001"), model());
    }

    public int calls() {
        return calls;
    }

    public List<String> prompts() {
        return List.copyOf(prompts);
    }
}
