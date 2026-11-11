package io.assay.cli;

import io.assay.support.AtomicFiles;
import java.nio.file.Files;
import java.nio.file.Path;

/** One-command onboarding: run the bundled example, then open the dashboard with the queue enabled. */
final class DemoCommand {

    private static final String DEMO_CASE =
            "{\"id\":\"DEMO-001\",\"scenario\":\"direct\",\"input\":{\"message\":\"hello agent\"},"
                    + "\"expected\":{\"route\":\"ECHO\",\"keyword\":\"hello\"}}";

    private DemoCommand() {
    }

    static int run(CommandLine.Namespace args) {
        Path output = args.path("output");
        Path store = args.path("store");
        Path cases = demoCases();
        String runId = "demo-" + CliIds.shortId();
        System.out.println("Assay demo: running the bundled EchoAdapter example...");
        int code = AssayCli.run(new String[] {
            "run",
            "--adapter", "io.assay.examples.EchoAdapter",
            "--suite", "demo",
            "--cases", cases.toString(),
            "--store", store.toString(),
            "--output", output.toString(),
            "--run-id", runId
        });
        if (code != 0) {
            System.err.println("Demo evaluation failed with exit code " + code);
            return code;
        }
        if (args.flag("no-serve")) {
            System.out.println("Next: java -jar app/target/assay.jar serve --output " + output
                    + " --store " + store + " --worker --open-browser");
            return 0;
        }

        System.out.println("Opening the dashboard with the job queue enabled...");
        String port = String.valueOf(args.integer("port"));
        if (args.flag("no-open")) {
            return AssayCli.run(new String[] {
                "serve", "--output", output.toString(), "--store", store.toString(),
                "--worker", "--port", port
            });
        }
        return AssayCli.run(new String[] {
            "serve", "--output", output.toString(), "--store", store.toString(),
            "--worker", "--port", port, "--open-browser"
        });
    }

    private static Path demoCases() {
        Path example = Path.of("examples", "cases.example.jsonl");
        if (Files.isRegularFile(example)) {
            return example;
        }
        Path fallback = Path.of(".assay", "demo", "cases.jsonl");
        AtomicFiles.createDirectories(fallback.getParent());
        AtomicFiles.writeText(fallback, DEMO_CASE + "\n");
        return fallback;
    }
}
