package io.assay.cli;

import io.assay.audit.ScopeAudit;
import io.assay.audit.ScopeAuditReport;
import io.assay.autoevolve.AutoEvolutionLoop;
import io.assay.diff.DiffAnalysis;
import io.assay.diff.DiffSnapshot;
import io.assay.engine.AdapterLoader;
import io.assay.engine.CaseLoader;
import io.assay.engine.EvaluationEngine;
import io.assay.evolve.EvolutionEngine;
import io.assay.json.Json;
import io.assay.llm.LlmReviewer;
import io.assay.llm.OpenAiCompatibleReviewer;
import io.assay.model.AutoEvolutionAdapter;
import io.assay.model.EvalCase;
import io.assay.model.EvolutionBudget;
import io.assay.model.EvolutionCandidate;
import io.assay.model.ProjectAdapter;
import io.assay.model.TestSelection;
import io.assay.report.Reporting;
import io.assay.review.ReviewSamples;
import io.assay.selection.TestSelectionService;
import io.assay.store.ResultStore;
import io.assay.support.AtomicFiles;
import io.assay.workspace.TextArtifactWorkspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code assay} command line.
 *
 * <p>Exit codes are part of the contract other tools depend on: {@code 0} success, {@code 1} a hard
 * gate failed or a decision was negative, {@code 2} a usage error or a result that requires human
 * review. The last of these is deliberate — a run that needs a person should not look like a pass.
 */
public final class AssayCli {

    private static final String PROGRAM = "assay";

    private AssayCli() {
    }

    public static void main(String[] argv) {
        System.exit(run(argv));
    }

    /** Runs the CLI and returns the process exit code; exposed for tests. */
    public static int run(String[] argv) {
        List<CommandLine.Spec> commands = List.of(
                new CommandLine.Spec("run", "run one externally supplied suite"),
                new CommandLine.Spec("select-tests", "recommend test suites from the current Git diff"),
                new CommandLine.Spec("audit-scope", "audit a committed AI code change against a task"),
                new CommandLine.Spec("release", "run targeted regression, smoke, then optional full"),
                new CommandLine.Spec("evolve", "compare a baseline and candidate across improvement, regression, and holdout"),
                new CommandLine.Spec("evolve-auto", "diagnose, generate, apply, and evaluate text candidates in a sandbox"),
                new CommandLine.Spec("review", "store the human final conclusion"),
                new CommandLine.Spec("export", "export human-confirmed regression or few-shot candidates"),
                new CommandLine.Spec("promote-review", "adjudicate one evaluator-Skill review sample"));
        if (argv.length == 0) {
            System.err.print(CommandLine.usage(PROGRAM, commands));
            return 2;
        }
        String command = argv[0];
        if ("-h".equals(command) || "--help".equals(command)) {
            System.out.print(CommandLine.usage(PROGRAM, commands));
            return 0;
        }
        String[] rest = java.util.Arrays.copyOfRange(argv, 1, argv.length);
        try {
            CommandLine.Spec spec = specFor(command, commands);
            if (spec == null) {
                System.err.print(CommandLine.usage(PROGRAM, commands));
                System.err.println(PROGRAM + ": error: unknown command: " + command);
                return 2;
            }
            CommandLine.Namespace args = CommandLine.parse(spec, rest);
            return switch (command) {
                case "run" -> runSuite(args);
                case "select-tests" -> selectTests(args);
                case "audit-scope" -> auditScope(args);
                case "release" -> release(args);
                case "evolve" -> evolve(args);
                case "evolve-auto" -> autoEvolve(args);
                case "review" -> review(args);
                case "export" -> export(args);
                default -> promoteReview(args);
            };
        } catch (CommandLine.UsageException error) {
            if (error.showUsage()) {
                System.err.print(CommandLine.usage(PROGRAM, commands));
            }
            System.err.println(PROGRAM + ": error: " + error.getMessage());
            return error.exitCode();
        } catch (RuntimeException error) {
            System.err.println(PROGRAM + ": error: " + error);
            return 1;
        }
    }

    private static CommandLine.Spec specFor(String command, List<CommandLine.Spec> commands) {
        return switch (command) {
            case "run" -> runtime(new CommandLine.Spec("run", "run one externally supplied suite"))
                    .required("adapter", CommandLine.Kind.STRING)
                    .option("adapter-class", CommandLine.Kind.STRING, null)
                    .required("suite", CommandLine.Kind.STRING)
                    .required("cases", CommandLine.Kind.STRING)
                    .option("run-id", CommandLine.Kind.STRING, null)
                    .flag("resume");
            case "select-tests" -> new CommandLine.Spec("select-tests", "recommend test suites from the current Git diff")
                    .option("repository", CommandLine.Kind.STRING, ".")
                    .option("base", CommandLine.Kind.STRING, "HEAD")
                    .option("ai-provider", CommandLine.Kind.STRING, "none", "none", "local", "remote")
                    .option("ai-input", CommandLine.Kind.STRING, "auto", "auto", "raw", "summary")
                    .option("confidence-threshold", CommandLine.Kind.DECIMAL, "0.7")
                    .option("output", CommandLine.Kind.STRING, null);
            case "audit-scope" -> new CommandLine.Spec("audit-scope", "audit a committed AI code change against a task")
                    .required("repository", CommandLine.Kind.STRING)
                    .required("base", CommandLine.Kind.STRING)
                    .required("target", CommandLine.Kind.STRING)
                    .required("spec", CommandLine.Kind.STRING)
                    .option("ai-provider", CommandLine.Kind.STRING, "none", "none", "local")
                    .option("adapter", CommandLine.Kind.STRING, null)
                    .option("adapter-class", CommandLine.Kind.STRING, null)
                    .option("regression", CommandLine.Kind.STRING, null)
                    .option("smoke", CommandLine.Kind.STRING, null)
                    .option("full", CommandLine.Kind.STRING, null)
                    .option("store", CommandLine.Kind.STRING, null)
                    .option("output", CommandLine.Kind.STRING, null)
                    .option("workers", CommandLine.Kind.INTEGER, "1")
                    .option("retries", CommandLine.Kind.INTEGER, "0")
                    .option("timeout", CommandLine.Kind.DECIMAL, "30");
            case "release" -> runtime(new CommandLine.Spec("release", "run targeted regression, smoke, then optional full"))
                    .required("adapter", CommandLine.Kind.STRING)
                    .option("adapter-class", CommandLine.Kind.STRING, null)
                    .required("regression", CommandLine.Kind.STRING)
                    .required("smoke", CommandLine.Kind.STRING)
                    .option("full", CommandLine.Kind.STRING, null)
                    .option("release-id", CommandLine.Kind.STRING, null);
            case "evolve" -> runtime(new CommandLine.Spec("evolve", "compare a baseline and candidate"))
                    .required("baseline-adapter", CommandLine.Kind.STRING)
                    .option("baseline-adapter-class", CommandLine.Kind.STRING, null)
                    .required("candidate-adapter", CommandLine.Kind.STRING)
                    .option("candidate-adapter-class", CommandLine.Kind.STRING, null)
                    .required("candidate", CommandLine.Kind.STRING)
                    .option("policy", CommandLine.Kind.STRING, null)
                    .required("improvement", CommandLine.Kind.STRING)
                    .required("regression", CommandLine.Kind.STRING)
                    .required("holdout", CommandLine.Kind.STRING)
                    .option("experiment-id", CommandLine.Kind.STRING, null);
            case "evolve-auto" -> runtime(new CommandLine.Spec("evolve-auto", "automatic text evolution in a sandbox"))
                    .required("auto-adapter", CommandLine.Kind.STRING)
                    .option("adapter-class", CommandLine.Kind.STRING, null)
                    .option("policy", CommandLine.Kind.STRING, null)
                    .required("improvement", CommandLine.Kind.STRING)
                    .required("regression", CommandLine.Kind.STRING)
                    .required("holdout", CommandLine.Kind.STRING)
                    .option("loop-id", CommandLine.Kind.STRING, null)
                    .option("workspace", CommandLine.Kind.STRING, ".assay/workspaces")
                    .option("max-rounds", CommandLine.Kind.INTEGER, "3")
                    .option("max-candidates-per-round", CommandLine.Kind.INTEGER, "3")
                    .option("max-elapsed-seconds", CommandLine.Kind.DECIMAL, null)
                    .option("max-evolver-calls", CommandLine.Kind.INTEGER, null)
                    .flag("resume");
            case "review" -> new CommandLine.Spec("review", "store the human final conclusion")
                    .option("store", CommandLine.Kind.STRING, ".assay/store")
                    .required("run-id", CommandLine.Kind.STRING)
                    .required("case-id", CommandLine.Kind.STRING)
                    .required(
                            "decision",
                            CommandLine.Kind.STRING,
                            "confirmed_badcase",
                            "false_positive",
                            "accepted",
                            "needs_follow_up")
                    .required("conclusion", CommandLine.Kind.STRING);
            case "export" -> new CommandLine.Spec("export", "export human-confirmed candidates")
                    .option("store", CommandLine.Kind.STRING, ".assay/store")
                    .required("kind", CommandLine.Kind.STRING, "regression", "few-shot")
                    .required("output", CommandLine.Kind.STRING);
            case "promote-review" -> new CommandLine.Spec("promote-review", "adjudicate one review sample")
                    .required("input", CommandLine.Kind.STRING)
                    .required("output", CommandLine.Kind.STRING)
                    .required(
                            "outcome",
                            CommandLine.Kind.STRING,
                            "CORRECT",
                            "PARTIALLY_CORRECT",
                            "INCORRECT",
                            "UNRESOLVED")
                    .required("role", CommandLine.Kind.STRING, "improvement", "regression", "holdout", "pending")
                    .required("conclusion", CommandLine.Kind.STRING)
                    .required("reviewer", CommandLine.Kind.STRING)
                    .option("reviewed-at", CommandLine.Kind.STRING, null);
            default -> null;
        };
    }

    private static CommandLine.Spec runtime(CommandLine.Spec spec) {
        return spec.option("store", CommandLine.Kind.STRING, ".assay/store")
                .option("output", CommandLine.Kind.STRING, ".assay/runs")
                .option("source", CommandLine.Kind.STRING, "online", "online", "offline")
                .option("workers", CommandLine.Kind.INTEGER, "1")
                .option("retries", CommandLine.Kind.INTEGER, "0")
                .option("timeout", CommandLine.Kind.DECIMAL, "30")
                .flag("use-llm")
                .flag("collect-few-shot")
                .option("run-identity", CommandLine.Kind.STRING, null);
    }

    private static LlmReviewer reviewer(boolean enabled) {
        if (!enabled) {
            return null;
        }
        OpenAiCompatibleReviewer configured = OpenAiCompatibleReviewer.fromEnvironment();
        if (configured == null) {
            throw new CommandLine.UsageException("--use-llm requires ASSAY_MODEL", 1, false);
        }
        return configured;
    }

    private static EvaluationEngine engineFor(
            CommandLine.Namespace args, ResultStore store, String adapterRef, String adapterClass) {
        ProjectAdapter adapter = AdapterLoader.loadProjectAdapter(adapterRef, adapterClass);
        return new EvaluationEngine(
                adapter,
                store,
                reviewer(args.flag("use-llm")),
                args.integer("workers"),
                args.integer("retries"),
                args.decimal("timeout"),
                args.flag("collect-few-shot"),
                args.string("run-identity"));
    }

    private static int runSuite(CommandLine.Namespace args) {
        try (ResultStore store = new ResultStore(args.path("store"))) {
            EvaluationEngine engine = engineFor(args, store, args.string("adapter"), args.string("adapter-class"));
            List<EvalCase> cases = CaseLoader.loadCases(args.path("cases"), args.string("suite"));
            Map<String, Object> summary =
                    engine.runSuite(cases, args.string("suite"), args.string("source"), args.string("run-id"),
                            args.flag("resume"));
            Path output = args.path("output").resolve(String.valueOf(summary.get("run_id")));
            Reporting.writeRunArtifacts(summary, output);
            printSummary(summary);
            System.out.println("report: " + output.resolve("report.md"));
            return intValue(summary.get("hard_failures")) > 0 ? 1 : 0;
        }
    }

    private static int selectTests(CommandLine.Namespace args) {
        TestSelectionService.JsonReviewer reviewer = null;
        if (!"none".equals(args.string("ai-provider"))) {
            reviewer = OpenAiCompatibleReviewer.fromEnvironment();
            if (reviewer == null) {
                throw new CommandLine.UsageException("AI selection requires ASSAY_MODEL", 1, false);
            }
        }
        TestSelection selection =
                TestSelectionService.selectTests(
                        args.path("repository"),
                        args.string("base"),
                        args.string("ai-provider"),
                        args.string("ai-input"),
                        reviewer,
                        args.decimal("confidence-threshold"),
                        null);
        String content = Json.write(selection.toJson(), 2);
        Path output = args.path("output");
        if (output != null) {
            createParent(output);
            AtomicFiles.writeText(output, content + "\n");
        }
        System.out.println(content);
        return selection.humanReviewRequired() ? 2 : 0;
    }

    /** Creates the output file's directory when it has one; a bare filename has none. */
    private static void createParent(Path output) {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            AtomicFiles.createDirectories(parent);
        }
    }

    private static int auditScope(CommandLine.Namespace args) {
        Map<String, Object> specification = Json.parseObject(AtomicFiles.readText(args.path("spec")));
        DiffSnapshot snapshot =
                DiffAnalysis.readGitDiff(args.path("repository"), args.string("base"), args.string("target"));
        boolean useAi = "local".equals(args.string("ai-provider"));
        OpenAiCompatibleReviewer jsonReviewer = useAi ? OpenAiCompatibleReviewer.fromEnvironment() : null;
        if (useAi && jsonReviewer == null) {
            throw new CommandLine.UsageException("--use-llm requires ASSAY_MODEL", 1, false);
        }
        TestSelectionService.JsonReviewer selectionReviewer = jsonReviewer;
        TestSelection selection =
                TestSelectionService.selectTests(
                        Path.of("."), "HEAD", args.string("ai-provider"), "auto", selectionReviewer, 0.7, snapshot);
        Map<String, Object> result = ScopeAudit.auditScope(snapshot, specification, selectionReviewer);
        result.put("test_selection", selection.toJson());

        Path output =
                args.path("output") != null
                        ? args.path("output")
                        : Path.of(".assay", "audits").resolve(shortId());
        List<String> missing = new ArrayList<>();
        List<Map<String, Object>> runs = new ArrayList<>();
        if (snapshot.files().isEmpty()) {
            missing.add("The commit comparison contains no changed files.");
        } else if (args.string("adapter") == null) {
            missing.add("No ProjectAdapter was supplied; selected regression suites were not executed.");
        } else {
            Path repository = args.path("repository").toAbsolutePath().normalize();
            if (!DiffAnalysis.git(repository, "rev-parse", "HEAD").trim().equals(snapshot.targetCommit())) {
                missing.add("Tests were not run because the repository HEAD is not the target commit.");
            } else if (!DiffAnalysis.git(repository, "status", "--porcelain").trim().isEmpty()) {
                missing.add("Tests were not run because the target checkout has uncommitted files.");
            } else {
                Map<String, Path> suitePaths = new LinkedHashMap<>();
                for (String suite : selection.suites()) {
                    suitePaths.put(suite, args.path(suite));
                }
                List<String> available = new ArrayList<>();
                for (Map.Entry<String, Path> entry : suitePaths.entrySet()) {
                    if (entry.getValue() == null) {
                        missing.add("Selected " + entry.getKey() + " suite has no case file.");
                    } else {
                        available.add(entry.getKey());
                    }
                }
                if (!available.isEmpty()) {
                    try (ResultStore store =
                            new ResultStore(
                                    args.provided("store") ? args.path("store") : output.resolve("store"))) {
                        EvaluationEngine engine =
                                new EvaluationEngine(
                                        AdapterLoader.loadProjectAdapter(args.string("adapter"), args.string("adapter-class")),
                                        store,
                                        null,
                                        args.integer("workers"),
                                        args.integer("retries"),
                                        args.decimal("timeout"),
                                        false,
                                        snapshot.targetCommit());
                        for (String suite : available) {
                            try {
                                Map<String, Object> summary =
                                        engine.runSuite(
                                                CaseLoader.loadCases(suitePaths.get(suite), suite),
                                                suite,
                                                "online",
                                                "scope-" + shortId() + "-" + suite,
                                                false);
                                Reporting.writeRunArtifacts(summary, output.resolve("tests").resolve(suite));
                                Map<String, Object> run = new LinkedHashMap<>();
                                run.put("suite", suite);
                                run.put("status", summary.get("status"));
                                run.put("case_count", summary.get("case_count"));
                                run.put("hard_failures", summary.get("hard_failures"));
                                run.put("report_path", "tests/" + suite + "/report.md");
                                runs.add(run);
                            } catch (RuntimeException error) {
                                missing.add(
                                        "Selected " + suite + " suite could not run: "
                                                + error.getClass().getSimpleName() + ": " + error.getMessage());
                            }
                        }
                    } catch (RuntimeException error) {
                        missing.add(
                                "Regression execution unavailable: " + error.getClass().getSimpleName() + ": "
                                        + error.getMessage());
                    }
                }
            }
        }

        String testStatus;
        if (runs.isEmpty()) {
            testStatus = "not_run";
        } else if (runs.stream().anyMatch(run -> "failed".equals(run.get("status")))) {
            testStatus = "failed";
        } else if (runs.size() < selection.suites().size()) {
            testStatus = "partial";
        } else {
            testStatus = "passed";
        }
        Map<String, Object> tests = new LinkedHashMap<>();
        tests.put("status", testStatus);
        tests.put("runs", runs);
        tests.put("selected_suites", selection.suites());
        result.put("tests", tests);

        for (Object warning : asList(result.get("warnings"))) {
            missing.add(String.valueOf(warning));
        }
        if (selection.humanReviewRequired()) {
            missing.addAll(selection.reviewReasons());
        }
        if (String.valueOf(result.get("requirement")).isEmpty() || asList(result.get("acceptance_criteria")).isEmpty()) {
            missing.add("Requirement and acceptance criteria must be specific enough for scope judgment.");
        }
        for (Object rawFinding : asList(result.get("findings"))) {
            if (asMap(rawFinding).get("line") == null) {
                missing.add("Some changed files have no source line evidence (binary or metadata-only change).");
                break;
            }
        }
        List<String> uncovered = new ArrayList<>();
        for (String item : missing) {
            if (!uncovered.contains(item)) {
                uncovered.add(item);
            }
        }
        result.put("uncovered_risks", uncovered);

        List<Object> findings = asList(result.get("findings"));
        String conclusion;
        if (findings.stream()
                .anyMatch(finding -> "clear_out_of_scope".equals(asMap(finding).get("status"))
                        && "rules".equals(asMap(finding).get("source")))) {
            conclusion = "clear_out_of_scope";
        } else if (findings.stream()
                        .anyMatch(finding -> List.of("clear_out_of_scope", "suspected_out_of_scope")
                                .contains(asMap(finding).get("status")))
                || "failed".equals(testStatus)) {
            conclusion = "review_required";
        } else if (findings.stream().anyMatch(finding -> "insufficient_evidence".equals(asMap(finding).get("status")))
                || !uncovered.isEmpty()
                || !"passed".equals(testStatus)) {
            conclusion = "insufficient_evidence";
        } else {
            conclusion = "no_obvious_issue";
        }
        result.put("conclusion", conclusion);
        ScopeAuditReport.writeArtifacts(result, output, snapshot.rawDiff());
        Map<String, Object> printed = new LinkedHashMap<>();
        printed.put("conclusion", conclusion);
        printed.put("tests", testStatus);
        System.out.println(Json.write(printed));
        System.out.println("report: " + output.resolve("report.md"));
        if ("no_obvious_issue".equals(conclusion)) {
            return 0;
        }
        return "clear_out_of_scope".equals(conclusion) ? 1 : 2;
    }

    private static int release(CommandLine.Namespace args) {
        List<EvaluationEngine.SuiteCases> suites = new ArrayList<>();
        suites.add(new EvaluationEngine.SuiteCases("regression", CaseLoader.loadCases(args.path("regression"), "regression")));
        suites.add(new EvaluationEngine.SuiteCases("smoke", CaseLoader.loadCases(args.path("smoke"), "smoke")));
        if (args.path("full") != null) {
            suites.add(new EvaluationEngine.SuiteCases("full", CaseLoader.loadCases(args.path("full"), "full")));
        }
        Map<String, Object> release;
        try (ResultStore store = new ResultStore(args.path("store"))) {
            release = engineFor(args, store, args.string("adapter"), args.string("adapter-class"))
                    .runRelease(suites, args.string("source"), args.string("release-id"));
        }
        Path output = args.path("output").resolve(String.valueOf(release.get("release_id")));
        AtomicFiles.createDirectories(output);
        for (Object rawStage : asList(release.get("stages"))) {
            Map<String, Object> stage = asMap(rawStage);
            Reporting.writeRunArtifacts(stage, output.resolve(String.valueOf(stage.get("suite"))));
        }
        AtomicFiles.writeText(output.resolve("release.json"), Json.write(release, 2));
        Map<String, Object> printed = new LinkedHashMap<>();
        printed.put("release_id", release.get("release_id"));
        printed.put("status", release.get("status"));
        System.out.println(Json.write(printed));
        System.out.println("release: " + output.resolve("release.json"));
        return "failed".equals(release.get("status")) ? 1 : 0;
    }

    private static int evolve(CommandLine.Namespace args) {
        Map<String, List<EvalCase>> datasets = new LinkedHashMap<>();
        datasets.put("improvement", CaseLoader.loadCases(args.path("improvement"), "improvement"));
        datasets.put("regression", CaseLoader.loadCases(args.path("regression"), "regression"));
        datasets.put("holdout", CaseLoader.loadCases(args.path("holdout"), "holdout"));
        EvolutionCandidate change = EvolutionEngine.loadCandidate(args.path("candidate"));
        Map<String, Object> result;
        try (ResultStore store = new ResultStore(args.path("store"))) {
            result =
                    new EvolutionEngine(
                                    engineFor(
                                            args,
                                            store,
                                            args.string("baseline-adapter"),
                                            args.string("baseline-adapter-class")),
                                    engineFor(
                                            args,
                                            store,
                                            args.string("candidate-adapter"),
                                            args.string("candidate-adapter-class")),
                                    EvolutionEngine.loadPolicy(args.path("policy")))
                            .run(change, datasets, args.string("source"), args.string("experiment-id"), false);
        }
        Path output = args.path("output").resolve(String.valueOf(result.get("experiment_id")));
        Reporting.writeEvolutionArtifacts(result, output);
        for (String role : List.of("improvement", "regression", "holdout")) {
            Reporting.writeRunArtifacts(asMap(asMap(result.get("baseline_runs")).get(role)), output.resolve("baseline").resolve(role));
            Reporting.writeRunArtifacts(asMap(asMap(result.get("candidate_runs")).get(role)), output.resolve("candidate").resolve(role));
        }
        Map<String, Object> printed = new LinkedHashMap<>();
        printed.put("experiment_id", result.get("experiment_id"));
        printed.put("candidate_id", change.candidateId());
        printed.put("decision", result.get("decision"));
        System.out.println(Json.write(printed));
        System.out.println("evolution: " + output.resolve("evolution_report.md"));
        return "accept".equals(result.get("decision")) ? 0 : 1;
    }

    private static int autoEvolve(CommandLine.Namespace args) {
        Map<String, List<EvalCase>> datasets = new LinkedHashMap<>();
        datasets.put("improvement", CaseLoader.loadCases(args.path("improvement"), "improvement"));
        datasets.put("regression", CaseLoader.loadCases(args.path("regression"), "regression"));
        datasets.put("holdout", CaseLoader.loadCases(args.path("holdout"), "holdout"));
        AutoEvolutionAdapter adapter =
                AdapterLoader.loadAutoEvolutionAdapter(args.string("auto-adapter"), args.string("adapter-class"));
        Map<String, Object> result;
        try (ResultStore store = new ResultStore(args.path("store"))) {
            result =
                    new AutoEvolutionLoop(
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
        for (Object rawRound : asList(result.get("rounds"))) {
            Map<String, Object> round = asMap(rawRound);
            for (Object rawCandidate : asList(round.get("candidates"))) {
                Map<String, Object> candidate = asMap(rawCandidate);
                Map<String, Object> evaluation = asMap(candidate.get("evaluation"));
                Path candidateOutput =
                        output.resolve("rounds")
                                .resolve(String.valueOf(round.get("round")))
                                .resolve(String.valueOf(asMap(evaluation.get("candidate")).get("candidate_id")));
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

    private static int review(CommandLine.Namespace args) {
        try (ResultStore store = new ResultStore(args.path("store"))) {
            store.saveReview(
                    args.string("run-id"), args.string("case-id"), args.string("decision"), args.string("conclusion"));
        }
        System.out.println("human conclusion saved");
        return 0;
    }

    private static int export(CommandLine.Namespace args) {
        String kind = args.string("kind");
        String decision = "regression".equals(kind) ? "confirmed_badcase" : "accepted";
        List<Map<String, Object>> results;
        try (ResultStore store = new ResultStore(args.path("store"))) {
            results = store.listReviewedResults(decision);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> result : results) {
            if ("regression".equals(kind)) {
                Map<String, Object> evalCase = asMap(result.get("case"));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", evalCase.get("case_id"));
                row.put("scenario", evalCase.get("scenario"));
                row.put("input", evalCase.get("payload"));
                row.put("expected", evalCase.get("expected"));
                row.put("metadata", evalCase.get("metadata"));
                row.put("human_final_conclusion", result.get("human_final_conclusion"));
                rows.add(row);
            } else if (!asMap(result.get("few_shot_candidate")).isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>(asMap(result.get("few_shot_candidate")));
                row.put("status", "human_accepted");
                row.put("human_final_conclusion", result.get("human_final_conclusion"));
                rows.add(row);
            }
        }
        Path output = args.path("output");
        createParent(output);
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> row : rows) {
            builder.append(Json.write(row)).append('\n');
        }
        AtomicFiles.writeText(output, builder.toString());
        System.out.println("exported " + rows.size() + " records to " + output);
        return 0;
    }

    private static int promoteReview(CommandLine.Namespace args) {
        String content = AtomicFiles.readText(args.path("input")).trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("review input is empty");
        }
        Map<String, Object> record = Json.parseObject(content);
        Path output = args.path("output");
        if (Files.exists(output)) {
            String recordId = String.valueOf(
                    record.get("id") != null ? record.get("id") : record.get("case_id"));
            for (String line : AtomicFiles.readText(output).split("\n", -1)) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> existing = Json.parseObject(line);
                String existingId = String.valueOf(
                        existing.get("id") != null ? existing.get("id") : existing.get("case_id"));
                if (existingId.equals(recordId)) {
                    record = existing;
                    break;
                }
            }
        }
        Map<String, Object> promoted =
                ReviewSamples.promoteReviewRecord(
                        record,
                        args.string("outcome"),
                        args.string("conclusion"),
                        args.string("role"),
                        args.string("reviewer"),
                        args.string("reviewed-at"));
        ReviewSamples.writeReviewRecord(output, promoted);
        Object id = promoted.get("id") != null ? promoted.get("id") : promoted.get("case_id");
        System.out.println("promoted " + id + " to " + output);
        return 0;
    }

    private static void printSummary(Map<String, Object> summary) {
        Map<String, Object> printed = new LinkedHashMap<>(summary);
        printed.remove("results");
        System.out.println(Json.write(printed));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
