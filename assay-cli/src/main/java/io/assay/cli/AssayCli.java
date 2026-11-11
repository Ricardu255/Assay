package io.assay.cli;

import io.assay.adapters.AdapterLoader;
import io.assay.engine.CaseLoader;
import io.assay.engine.CircuitBreaker;
import io.assay.engine.EvaluationEngine;
import io.assay.engine.EvaluationListener;
import io.assay.engine.LlmReviewer;
import io.assay.execution.Stores;
import io.assay.json.Json;
import io.assay.llm.OpenAiCompatibleReviewer;
import io.assay.model.EvalCase;
import io.assay.model.EvaluationBudget;
import io.assay.model.ProjectAdapter;
import io.assay.model.TestSelection;
import io.assay.model.Values;
import io.assay.report.Reporting;
import io.assay.runner.RetryPolicy;
import io.assay.selection.TestSelectionService;
import io.assay.store.Results;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code assay} command line.
 *
 * <p>Exit codes are part of the contract other tools depend on: {@code 0} success, {@code 1} a hard
 * gate failed or a decision was negative, {@code 2} a usage error or a result that requires human
 * review. The last of these is deliberate — a run that needs a person should not look like a pass.
 */
public final class AssayCli {

    private static final String PROGRAM = "assay";

    @FunctionalInterface
    private interface Handler {
        int handle(CommandLine.Namespace args);
    }

    private record Command(CommandLine.Spec spec, Handler handler) {
        String name() {
            return spec.name();
        }
    }

    /** Every subcommand in help order; the single source for parsing, dispatch, and usage text. */
    private static final List<Command> COMMANDS = List.of(
            new Command(
                    new CommandLine.Spec("demo", "run the bundled example and open the dashboard")
                            .option("output", CommandLine.Kind.STRING, ".assay/runs")
                            .option("store", CommandLine.Kind.STRING, ".assay/store")
                            .option("port", CommandLine.Kind.INTEGER, "8080")
                            .flag("no-open")
                            .flag("no-serve"),
                    DemoCommand::run),
            new Command(
                    queueOptions(new CommandLine.Spec("serve", "view report artifacts in a local read-only dashboard")
                                    .option("output", CommandLine.Kind.STRING, ".assay/runs")
                                    .option("port", CommandLine.Kind.INTEGER, "8080"))
                            .flag("worker")
                            .flag("open-browser"),
                    QueueCommands::serve),
            new Command(
                    new CommandLine.Spec("quickstart", "create and run a first HTTP evaluation")
                            .option("endpoint", CommandLine.Kind.STRING, null)
                            .option("output-path", CommandLine.Kind.STRING, "output")
                            .option("keyword", CommandLine.Kind.STRING, "hello")
                            .option("events-path", CommandLine.Kind.STRING, null)
                            .option("request", CommandLine.Kind.STRING, "{\"message\":\"hello agent\"}")
                            .option("dir", CommandLine.Kind.STRING, ".assay/quickstart")
                            .option("workers", CommandLine.Kind.INTEGER, "1")
                            .option("timeout", CommandLine.Kind.DECIMAL, "30")
                            .flag("generate-only")
                            .flag("no-serve"),
                    AssayCli::quickstart),
            new Command(
                    runtime(new CommandLine.Spec("submit", "queue an evaluation run"))
                            .required("adapter", CommandLine.Kind.STRING)
                            .option("adapter-class", CommandLine.Kind.STRING, null)
                            .required("suite", CommandLine.Kind.STRING)
                            .required("cases", CommandLine.Kind.STRING)
                            .option("run-id", CommandLine.Kind.STRING, null)
                            .option("idempotency-key", CommandLine.Kind.STRING, null)
                            .option("max-attempts", CommandLine.Kind.INTEGER, "3"),
                    QueueCommands::submit),
            new Command(
                    queueOptions(new CommandLine.Spec("worker", "execute queued evaluation jobs"))
                            .flag("once"),
                    QueueCommands::worker),
            new Command(
                    new CommandLine.Spec("jobs", "list queued evaluation jobs")
                            .option("store", CommandLine.Kind.STRING, ".assay/store")
                            .option("limit", CommandLine.Kind.INTEGER, "20"),
                    QueueCommands::jobs),
            new Command(
                    new CommandLine.Spec("cancel", "cancel a queued or running job")
                            .option("store", CommandLine.Kind.STRING, ".assay/store")
                            .required("job-id", CommandLine.Kind.STRING),
                    QueueCommands::cancelJob),
            new Command(
                    new CommandLine.Spec("replay", "replay a failed evaluation job")
                            .option("store", CommandLine.Kind.STRING, ".assay/store")
                            .required("job-id", CommandLine.Kind.STRING)
                            .option("max-attempts", CommandLine.Kind.INTEGER, "3")
                            .option("max-replays", CommandLine.Kind.INTEGER, "3"),
                    QueueCommands::replayJob),
            new Command(
                    new CommandLine.Spec("prune", "delete old runs and terminal job records")
                            .option("store", CommandLine.Kind.STRING, ".assay/store")
                            .option("output", CommandLine.Kind.STRING, ".assay/runs")
                            .option("keep-days", CommandLine.Kind.INTEGER, "30")
                            .option("keep-runs", CommandLine.Kind.INTEGER, "100")
                            .option("keep-jobs", CommandLine.Kind.INTEGER, "500")
                            .flag("dry-run"),
                    QueueCommands::prune),
            new Command(
                    new CommandLine.Spec("reindex-jobs", "rebuild the file job run_id index")
                            .option("store", CommandLine.Kind.STRING, ".assay/store"),
                    QueueCommands::reindexJobs),
            new Command(
                    runtime(new CommandLine.Spec("run", "run one externally supplied suite"))
                            .required("adapter", CommandLine.Kind.STRING)
                            .option("adapter-class", CommandLine.Kind.STRING, null)
                            .required("suite", CommandLine.Kind.STRING)
                            .required("cases", CommandLine.Kind.STRING)
                            .option("run-id", CommandLine.Kind.STRING, null)
                            .flag("resume"),
                    AssayCli::runSuite),
            new Command(
                    new CommandLine.Spec("select-tests", "recommend test suites from the current Git diff")
                            .option("repository", CommandLine.Kind.STRING, ".")
                            .option("base", CommandLine.Kind.STRING, "HEAD")
                            .option("ai-provider", CommandLine.Kind.STRING, "none", "none", "local", "remote")
                            .option("ai-input", CommandLine.Kind.STRING, "auto", "auto", "raw", "summary")
                            .option("confidence-threshold", CommandLine.Kind.DECIMAL, "0.7")
                            .option("output", CommandLine.Kind.STRING, null),
                    AssayCli::selectTests),
            new Command(
                    new CommandLine.Spec("audit-scope", "audit a committed AI code change against a task")
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
                            .option("timeout", CommandLine.Kind.DECIMAL, "30"),
                    AssayCli::auditScope),
            new Command(
                    runtime(new CommandLine.Spec("release", "run targeted regression, smoke, then optional full"))
                            .required("adapter", CommandLine.Kind.STRING)
                            .option("adapter-class", CommandLine.Kind.STRING, null)
                            .required("regression", CommandLine.Kind.STRING)
                            .required("smoke", CommandLine.Kind.STRING)
                            .option("full", CommandLine.Kind.STRING, null)
                            .option("release-id", CommandLine.Kind.STRING, null),
                    EvolutionCommands::release),
            new Command(
                    new CommandLine.Spec("compare", "compare two stored runs with a flat release gate")
                            .required("baseline", CommandLine.Kind.STRING)
                            .required("candidate", CommandLine.Kind.STRING)
                            .option("store", CommandLine.Kind.STRING, ".assay/store")
                            .option("config", CommandLine.Kind.STRING, null)
                            .option("profile", CommandLine.Kind.STRING, "balanced", "none", "balanced", "strict")
                            .option("format", CommandLine.Kind.STRING, "markdown", "markdown", "json")
                            .option("output", CommandLine.Kind.STRING, null),
                    CompareCommand::compare),
            new Command(
                    runtime(new CommandLine.Spec(
                                    "evolve",
                                    "compare a baseline and candidate across improvement, regression, and holdout"))
                            .required("baseline-adapter", CommandLine.Kind.STRING)
                            .option("baseline-adapter-class", CommandLine.Kind.STRING, null)
                            .required("candidate-adapter", CommandLine.Kind.STRING)
                            .option("candidate-adapter-class", CommandLine.Kind.STRING, null)
                            .required("candidate", CommandLine.Kind.STRING)
                            .option("policy", CommandLine.Kind.STRING, null)
                            .required("improvement", CommandLine.Kind.STRING)
                            .required("regression", CommandLine.Kind.STRING)
                            .required("holdout", CommandLine.Kind.STRING)
                            .option("experiment-id", CommandLine.Kind.STRING, null),
                    EvolutionCommands::evolve),
            new Command(
                    runtime(new CommandLine.Spec(
                                    "evolve-auto",
                                    "diagnose, generate, apply, and evaluate text candidates in a sandbox"))
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
                            .flag("resume"),
                    EvolutionCommands::autoEvolve),
            new Command(
                    new CommandLine.Spec("review", "store the human final conclusion")
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
                            .required("conclusion", CommandLine.Kind.STRING),
                    ReviewCommands::review),
            new Command(
                    new CommandLine.Spec("export", "export human-confirmed regression or few-shot candidates")
                            .option("store", CommandLine.Kind.STRING, ".assay/store")
                            .required("kind", CommandLine.Kind.STRING, "regression", "few-shot")
                            .required("output", CommandLine.Kind.STRING),
                    ReviewCommands::export),
            new Command(
                    new CommandLine.Spec("promote-review", "adjudicate one evaluator-Skill review sample")
                            .required("input", CommandLine.Kind.STRING)
                            .required("output", CommandLine.Kind.STRING)
                            .required(
                                    "outcome",
                                    CommandLine.Kind.STRING,
                                    "CORRECT",
                                    "PARTIALLY_CORRECT",
                                    "INCORRECT",
                                    "UNRESOLVED")
                            .required(
                                    "role",
                                    CommandLine.Kind.STRING,
                                    "improvement",
                                    "regression",
                                    "holdout",
                                    "pending")
                            .required("conclusion", CommandLine.Kind.STRING)
                            .required("reviewer", CommandLine.Kind.STRING)
                            .option("reviewed-at", CommandLine.Kind.STRING, null),
                    ReviewCommands::promoteReview));

    private AssayCli() {
    }

    public static void main(String[] argv) {
        System.exit(run(argv));
    }

    /** Runs the CLI and returns the process exit code; exposed for tests. */
    public static int run(String[] argv) {
        if (argv.length == 0) {
            System.err.print(CommandLine.usage(PROGRAM, specs()));
            return 2;
        }
        String command = argv[0];
        if ("-h".equals(command) || "--help".equals(command)) {
            System.out.print(CommandLine.usage(PROGRAM, specs()));
            return 0;
        }
        String[] rest = Arrays.copyOfRange(argv, 1, argv.length);
        try {
            Command selected = select(command);
            if (selected == null) {
                System.err.print(CommandLine.usage(PROGRAM, specs()));
                System.err.println(PROGRAM + ": error: unknown command: " + command);
                return 2;
            }
            CommandLine.Namespace args = CommandLine.parse(selected.spec(), rest);
            return selected.handler().handle(args);
        } catch (CommandLine.UsageException error) {
            if (error.showUsage()) {
                System.err.print(CommandLine.usage(PROGRAM, specs()));
            }
            System.err.println(PROGRAM + ": error: " + error.getMessage());
            return error.exitCode();
        } catch (RuntimeException error) {
            System.err.println(PROGRAM + ": error: " + error);
            return 1;
        }
    }

    private static List<CommandLine.Spec> specs() {
        return COMMANDS.stream().map(Command::spec).toList();
    }

    private static Command select(String name) {
        return COMMANDS.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElse(null);
    }


    private static CommandLine.Spec queueOptions(CommandLine.Spec spec) {
        return spec.option("store", CommandLine.Kind.STRING, ".assay/store")
                .option("worker-id", CommandLine.Kind.STRING, null)
                .option("lease-seconds", CommandLine.Kind.INTEGER, "30")
                .option("poll", CommandLine.Kind.DECIMAL, "2")
                .option("retry-base-seconds", CommandLine.Kind.DECIMAL, "1")
                .option("retry-max-seconds", CommandLine.Kind.DECIMAL, "16")
                .option("retry-jitter", CommandLine.Kind.DECIMAL, "0.2");
    }

    private static CommandLine.Spec runtime(CommandLine.Spec spec) {
        return spec.option("store", CommandLine.Kind.STRING, ".assay/store")
                .option("output", CommandLine.Kind.STRING, ".assay/runs")
                .option("source", CommandLine.Kind.STRING, "online", "online", "offline")
                .option("workers", CommandLine.Kind.INTEGER, "1")
                .option("retries", CommandLine.Kind.INTEGER, "0")
                .option("timeout", CommandLine.Kind.DECIMAL, "30")
                .option("max-total-tokens", CommandLine.Kind.INTEGER, null)
                .option("max-cost-usd", CommandLine.Kind.DECIMAL, null)
                .option("reserve-output-tokens", CommandLine.Kind.INTEGER, "512")
                .option("reserve-cost-usd", CommandLine.Kind.DECIMAL, "0.01")
                .option("retry-base-seconds", CommandLine.Kind.DECIMAL, "1")
                .option("retry-max-seconds", CommandLine.Kind.DECIMAL, "16")
                .option("retry-jitter", CommandLine.Kind.DECIMAL, "0.2")
                .option("breaker-failures", CommandLine.Kind.INTEGER, "5")
                .option("breaker-reset-seconds", CommandLine.Kind.DECIMAL, "30")
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

    static EvaluationEngine engineFor(
            CommandLine.Namespace args, Results store, String adapterRef, String adapterClass) {
        ProjectAdapter adapter = AdapterLoader.loadProjectAdapter(adapterRef, adapterClass);
        EvaluationBudget budget = new EvaluationBudget(
                args.integerOrNull("max-total-tokens"),
                args.decimalOrNull("max-cost-usd"),
                args.integer("reserve-output-tokens"),
                args.decimal("reserve-cost-usd"));
        RetryPolicy retryPolicy = QueueCommands.retryPolicy(args);
        CircuitBreaker adapterBreaker = breaker(args);
        LlmReviewer reviewer = reviewer(args.flag("use-llm"));
        CircuitBreaker reviewerBreaker = reviewer == null ? CircuitBreaker.disabled() : breaker(args);
        return new EvaluationEngine(
                adapter,
                store,
                reviewer,
                args.integer("workers"),
                args.integer("retries"),
                args.decimal("timeout"),
                args.flag("collect-few-shot"),
                args.string("run-identity"),
                EvaluationListener.NOOP,
                budget,
                retryPolicy,
                adapterBreaker,
                reviewerBreaker);
    }

    private static CircuitBreaker breaker(CommandLine.Namespace args) {
        return new CircuitBreaker(
                args.integer("breaker-failures"),
                Duration.ofMillis(Math.round(args.decimal("breaker-reset-seconds") * 1000)));
    }



    private static int runSuite(CommandLine.Namespace args) {
        try (Results store = Stores.open(args.string("store"))) {
            EvaluationEngine engine = engineFor(args, store, args.string("adapter"), args.string("adapter-class"));
            List<EvalCase> cases = CaseLoader.loadCases(args.path("cases"), args.string("suite"));
            Map<String, Object> summary =
                    engine.runSuite(cases, args.string("suite"), args.string("source"), args.string("run-id"),
                            args.flag("resume"));
            Path output = args.path("output").resolve(String.valueOf(summary.get("run_id")));
            Reporting.writeRunArtifacts(summary, output);
            printSummary(summary);
            System.out.println("report: " + output.resolve("report.md"));
            return Values.numberAsInt(summary.get("hard_failures")) > 0
                            || "budget_exhausted".equals(summary.get("status"))
                    ? 1
                    : 0;
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
    static void createParent(Path output) {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            AtomicFiles.createDirectories(parent);
        }
    }

    private static int auditScope(CommandLine.Namespace args) {
        return AuditScopeCommand.run(args);
    }



    private static int quickstart(CommandLine.Namespace args) {
        return QuickstartCommand.run(args);
    }

    private static void printSummary(Map<String, Object> summary) {
        Map<String, Object> printed = new LinkedHashMap<>(summary);
        printed.remove("results");
        System.out.println(Json.write(printed));
    }

}
