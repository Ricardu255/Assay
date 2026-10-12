package io.assay.model;

import static org.junit.jupiter.api.Assertions.*;

import io.assay.json.Json;
import io.assay.engine.EvaluationEngine;
import io.assay.process.AgentProcessRunner;
import io.assay.store.ResultStore;
import io.assay.testing.Fixtures;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EvolutionConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-Infinity", "invalid", "-0.1", "1.1"})
    void rejectsInvalidRateConfiguration(String value) {
        assertThrows(IllegalArgumentException.class, () -> ScenarioGate.fromDict(
                Map.of("scenario", "critical", "minimum_hard_pass_rate", value)));
        assertThrows(IllegalArgumentException.class, () -> EvolutionPolicy.fromDict(
                Map.of("max_holdout_hard_pass_drop", value)));
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.1})
    void validatesDirectJavaConstructorsToo(double value) {
        assertThrows(IllegalArgumentException.class, () -> new ScenarioGate("critical", List.of("holdout"), value));
        assertThrows(IllegalArgumentException.class, () -> new EvolutionPolicy(List.of(), List.of(), value));
    }

    @Test
    void rejectsFractionalOverflowingAndNonNumericCounts() {
        for (Object value : List.of(1.5, 2147483648L, "NaN", "invalid", true)) {
            assertThrows(IllegalArgumentException.class,
                    () -> EvolutionPolicy.fromDict(Map.of("max_holdout_soft_warning_increase", value)));
            assertThrows(IllegalArgumentException.class,
                    () -> ScenarioGate.fromDict(Map.of("scenario", "critical", "minimum_case_count", value)));
        }
    }

    @Test
    void rejectsInvalidObjectivesAndBudgets() {
        assertThrows(IllegalArgumentException.class, () -> new MetricObjective("quality", "score", "typo", "mean", 0));
        assertThrows(IllegalArgumentException.class, () -> new MetricObjective("quality", "score", "maximize", "typo", 0));
        assertThrows(IllegalArgumentException.class, () -> new MetricObjective("quality", "score", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> MetricObjective.fromDict(
                Map.of("name", "quality", "metric", "score", "maximum_regression", "invalid")));
        assertThrows(IllegalArgumentException.class, () -> new EvolutionBudget(1, 1, Double.NaN, null));
        assertThrows(IllegalArgumentException.class, () -> new EvolutionBudget(1, 1, Double.POSITIVE_INFINITY, null));
        assertThrows(IllegalArgumentException.class, () -> new ScenarioGate("critical", List.of("typo"), 0.9));
    }

    @Test
    void rejectsMalformedPolicyFieldsAndDuplicateObjectiveNames() {
        assertThrows(IllegalArgumentException.class, () -> EvolutionPolicy.fromDict(Map.of("require_regression_pass", "false")));
        assertThrows(IllegalArgumentException.class, () -> EvolutionPolicy.fromDict(Map.of("objectives", "typo")));
        assertThrows(IllegalArgumentException.class, () -> EvolutionPolicy.fromDict(Map.of("scenario_gates", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> new EvolutionPolicy(false,
                List.of(new MetricObjective("same", "cost_usd"), new MetricObjective("same", "total_tokens"))));
    }

    @Test
    void keepsDefaultsAndValidRoundTrips() {
        EvolutionPolicy defaults = new EvolutionPolicy();
        assertEquals(defaults, EvolutionPolicy.fromDict(Map.of()));
        EvolutionPolicy configured = new EvolutionPolicy(List.of(new MetricObjective("quality", "score", 0.1)),
                List.of(new ScenarioGate("critical")), 0.1);
        assertEquals(configured, EvolutionPolicy.fromDict(Fixtures.asMap(Json.normalize(configured.toJson()))));
    }

    @Test
    void invalidExecutionLimitsFailBeforeCallingTheAdapterOrProcess(@TempDir Path root) {
        ProjectAdapter adapter = new ProjectAdapter("unused", (evalCase, context) -> {
            throw new AssertionError("invalid execution limits must not call the adapter");
        }, (handle, evalCase) -> null, List.of(), List.of());
        try (ResultStore store = new ResultStore(root.resolve("store"))) {
            for (double timeout : List.of(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
                assertThrows(IllegalArgumentException.class,
                        () -> new EvaluationEngine(adapter, store, null, 1, 0, timeout, false, null));
                assertThrows(IllegalArgumentException.class,
                        () -> AgentProcessRunner.runAgentProcess(List.of("unused"), root, null, timeout, Map.of(), 1024));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> new EvaluationEngine(adapter, store, null, 0, 0, 30, false, null));
            assertThrows(IllegalArgumentException.class,
                    () -> new EvaluationEngine(adapter, store, null, Integer.MAX_VALUE, 0, 30, false, null));
            assertThrows(IllegalArgumentException.class,
                    () -> new EvaluationEngine(adapter, store, null, 1, -1, 30, false, null));
        }
    }
}
