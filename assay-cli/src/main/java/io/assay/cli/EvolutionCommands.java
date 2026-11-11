package io.assay.cli;

import io.assay.adapters.AdapterLoader;
import io.assay.autoevolve.AutoEvolutionLoop;
import io.assay.engine.CaseLoader;
import io.assay.engine.EvaluationEngine;
import io.assay.evolve.EvolutionEngine;
import io.assay.execution.Stores;
import io.assay.json.Json;
import io.assay.model.AutoEvolutionAdapter;
import io.assay.model.EvalCase;
import io.assay.model.EvolutionBudget;
import io.assay.model.EvolutionCandidate;
import io.assay.model.Values;
import io.assay.report.Reporting;
import io.assay.store.Results;
import io.assay.support.AtomicFiles;
import io.assay.workspace.TextArtifactWorkspace;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Implements the {@code release}, {@code evolve}, and {@code evolve-auto} commands. */
final class EvolutionCommands {

    private EvolutionCommands() {
    }

    static int release(CommandLine.Namespace args) {
        List<EvaluationEngine.SuiteCases> suites = new ArrayList<>();
        suites.add(new EvaluationEngine.SuiteCases(
                "regression", CaseLoader.loadCases(args.path("regression"), "regression")));
        suites.add(new EvaluationEngine.SuiteCases(
                "smoke", CaseLoader.loadCases(args.path("smoke"), "smoke")));
        if (args.path("full") != null) {
            suites.add(new EvaluationEngine.SuiteCases(
                    "full", CaseLoader.loadCases(args.path("full"), "full")));
        }
        Map<String, Object> release;
        try (Results store = Stores.open(args.string("store"))) {
            release = AssayCli.engineFor(args, store, args.string("adapter"), args.string("adapter-class"))
                    .runRelease(suites, args.string("source"), args.string("release-id"));
        }
        Path output = args.path("output").resolve(String.valueOf(release.get("release_id")));
        AtomicFiles.createDirectories(output);
        for (Object rawStage : Values.asList(release.get("stages"))) {
            Map<String, Object> stage = Values.asMap(rawStage);
            Reporting.writeRunArtifacts(stage, output.resolve(String.valueOf(stage.get("suite"))));
        }
        AtomicFiles.writeText(output.resolve("release.json"), Json.write(release, 2));
        Map<String, Object> printed = new LinkedHashMap<>();
        printed.put("release_id", release.get("release_id"));
        printed.put("status", release.get("status"));
        System.out.println(Json.write(printed));
        System.out.println("release: " + output.resolve("release.json"));
        return "failed".equals(release.get("status"))
                        || "budget_exhausted".equals(release.get("status"))
                ? 1
                : 0;
    }

    static int evolve(CommandLine.Namespace args) {
        Map<String, List<EvalCase>> datasets = new LinkedHashMap<>();
        datasets.put("improvement", CaseLoader.loadCases(args.path("improvement"), "improvement"));
        datasets.put("regression", CaseLoader.loadCases(args.path("regression"), "regression"));
        datasets.put("holdout", CaseLoader.loadCases(args.path("holdout"), "holdout"));
        EvolutionCandidate change = EvolutionEngine.loadCandidate(args.path("candidate"));
        Map<String, Object> result;
        try (Results store = Stores.open(args.string("store"))) {
            result = new EvolutionEngine(
                            AssayCli.engineFor(
                                    args, store, args.string("baseline-adapter"),
                                    args.string("baseline-adapter-class")),
                            AssayCli.engineFor(
                                    args, store, args.string("candidate-adapter"),
                                    args.string("candidate-adapter-class")),
                            EvolutionEngine.loadPolicy(args.path("policy")))
                    .run(change, datasets, args.string("source"), args.string("experiment-id"), false);
        }
        Path output = args.path("output").resolve(String.valueOf(result.get("experiment_id")));
        Reporting.writeEvolutionArtifacts(result, output);
        for (String role : List.of("improvement", "regression", "holdout")) {
            Reporting.writeRunArtifacts(
                    Values.asMap(Values.asMap(result.get("baseline_runs")).get(role)),
                    output.resolve("baseline").resolve(role));
            Reporting.writeRunArtifacts(
                    Values.asMap(Values.asMap(result.get("candidate_runs")).get(role)),
                    output.resolve("candidate").resolve(role));
        }
        Map<String, Object> printed = new LinkedHashMap<>();
        printed.put("experiment_id", result.get("experiment_id"));
        printed.put("candidate_id", change.candidateId());
        printed.put("decision", result.get("decision"));
        System.out.println(Json.write(printed));
        System.out.println("evolution: " + output.resolve("evolution_report.md"));
        return "accept".equals(result.get("decision")) ? 0 : 1;
    }

    static int autoEvolve(CommandLine.Namespace args) {
        Map<String, List<EvalCase>> datasets = new LinkedHashMap<>();
        datasets.put("improvement", CaseLoader.loadCases(args.path("improvement"), "improvement"));
        datasets.put("regression", CaseLoader.loadCases(args.path("regression"), "regression"));
        datasets.put("holdout", CaseLoader.loadCases(args.path("holdout"), "holdout"));
        AutoEvolutionAdapter adapter = AdapterLoader.loadAutoEvolutionAdapter(
                args.string("auto-adapter"), args.string("adapter-class"));
        Map<String, Object> result;
        try (Results store = Stores.open(args.string("store"))) {
            result = new AutoEvolutionLoop(
                            store,
                            new TextArtifactWorkspace(args.path("workspace")),
                            EvolutionEngine.loadPolicy(args.path("policy")),
                            args.integer("workers"),
                            args.integer("retries"),
                            args.decimal("timeout"))
                    .run(
                            adapter,
                            datasets,
                            new EvolutionBudget(
                                    args.integer("max-rounds"),
                                    args.integer("max-candidates-per-round"),
                                    args.decimalOrNull("max-elapsed-seconds"),
                                    args.integerOrNull("max-evolver-calls")),
                            args.string("loop-id"),
                            args.string("source"),
                            args.flag("resume"));
        }
        Path output = args.path("output").resolve(String.valueOf(result.get("loop_id")));
        Reporting.writeAutoEvolutionArtifacts(result, output);
        for (Object rawRound : Values.asList(result.get("rounds"))) {
            Map<String, Object> round = Values.asMap(rawRound);
            for (Object rawCandidate : Values.asList(round.get("candidates"))) {
                Map<String, Object> candidate = Values.asMap(rawCandidate);
                Map<String, Object> evaluation = Values.asMap(candidate.get("evaluation"));
                Path candidateOutput = output.resolve("rounds")
                        .resolve(String.valueOf(round.get("round")))
                        .resolve(String.valueOf(
                                Values.asMap(evaluation.get("candidate")).get("candidate_id")));
                Reporting.writeEvolutionArtifacts(evaluation, candidateOutput);
            }
        }
        Map<String, Object> printed = new LinkedHashMap<>();
        printed.put("loop_id", result.get("loop_id"));
        printed.put("status", result.get("status"));
        printed.put("current_version", result.get("current_version"));
        System.out.println(Json.write(printed));
        System.out.println("auto evolution: " + output.resolve("auto_evolution_report.md"));
        return "completed".equals(result.get("status")) ? 0 : 1;
    }
}
