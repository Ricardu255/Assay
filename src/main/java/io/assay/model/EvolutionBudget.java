package io.assay.model;

import io.assay.json.Jsonable;
import java.util.LinkedHashMap;
import java.util.Map;

/** Caps on an automatic evolution loop. */
public record EvolutionBudget(
        int maxRounds, int maxCandidatesPerRound, Double maxElapsedSeconds, Integer maxEvolverCalls)
        implements Jsonable {

    public EvolutionBudget {
        if (maxRounds < 1 || maxCandidatesPerRound < 1) {
            throw new IllegalArgumentException("evolution budget values must be positive");
        }
        if (maxElapsedSeconds != null && maxElapsedSeconds <= 0) {
            throw new IllegalArgumentException("max_elapsed_seconds must be positive");
        }
        if (maxEvolverCalls != null && maxEvolverCalls < 1) {
            throw new IllegalArgumentException("max_evolver_calls must be positive");
        }
    }

    public EvolutionBudget() {
        this(3, 3, null, null);
    }

    public EvolutionBudget(int maxRounds, int maxCandidatesPerRound) {
        this(maxRounds, maxCandidatesPerRound, null, null);
    }

    public EvolutionBudget(int maxRounds, int maxCandidatesPerRound, Integer maxEvolverCalls) {
        this(maxRounds, maxCandidatesPerRound, null, maxEvolverCalls);
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("max_rounds", maxRounds);
        result.put("max_candidates_per_round", maxCandidatesPerRound);
        result.put("max_elapsed_seconds", maxElapsedSeconds);
        result.put("max_evolver_calls", maxEvolverCalls);
        return result;
    }
}
