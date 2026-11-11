package io.assay.cli;

import io.assay.json.Json;
import io.assay.support.AtomicFiles;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Implements the interactive {@code quickstart} command. */
final class QuickstartCommand {

    private static final BufferedReader INPUT =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    private QuickstartCommand() {
    }

    static int run(CommandLine.Namespace args) {
        System.out.println("Assay quickstart - answer a few questions, then the report opens.");
        String endpoint = args.string("endpoint");
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = ask("Agent HTTP endpoint", "");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new CommandLine.UsageException("endpoint is required", 2, false);
        }
        String outputPath = args.provided("output-path")
                ? args.string("output-path")
                : ask("JSON field containing the answer", "output");
        String keyword = args.provided("keyword")
                ? args.string("keyword")
                : ask("Expected keyword in the answer", "hello");
        if (outputPath == null || outputPath.isBlank() || keyword == null || keyword.isBlank()) {
            throw new CommandLine.UsageException("output-path and keyword must be non-empty", 2, false);
        }
        Path dir = args.path("dir");
        AtomicFiles.createDirectories(dir);
        Path config = dir.resolve("my-agent.http.json");
        Path cases = dir.resolve("cases.jsonl");

        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("name", "answer");
        gate.put("actual", "final_output");
        gate.put("operator", "contains");
        gate.put("expected", "keyword");
        Map<String, Object> http = new LinkedHashMap<>();
        http.put("type", "http");
        http.put("name", "my-agent");
        http.put("endpoint", endpoint);
        http.put("output_path", outputPath);
        if (args.string("events-path") != null && !args.string("events-path").isBlank()) {
            http.put("events_path", args.string("events-path"));
        }
        http.put("hard_gates", List.of(gate));
        AtomicFiles.writeText(config, Json.write(http, 2) + "\n");

        Object request;
        try {
            request = Json.parseObject(args.string("request"));
        } catch (RuntimeException error) {
            throw new CommandLine.UsageException("request must be a JSON object", 2, false);
        }
        List<String> lines = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            Map<String, Object> evalCase = new LinkedHashMap<>();
            evalCase.put("id", "Q-" + index);
            evalCase.put("input", request);
            evalCase.put("expected", Map.of("keyword", keyword));
            lines.add(Json.write(evalCase));
        }
        AtomicFiles.writeText(cases, String.join("\n", lines) + "\n");
        System.out.println("Created " + config.toAbsolutePath());
        System.out.println("Created " + cases.toAbsolutePath());
        if (args.flag("generate-only")) {
            System.out.println("Next: java -jar app/target/assay.jar run --adapter " + config
                    + " --suite quickstart --cases " + cases);
            return 0;
        }

        System.out.println("Running the first evaluation...");
        int code = AssayCli.run(new String[] {
            "run",
            "--adapter", config.toString(),
            "--suite", "quickstart",
            "--cases", cases.toString(),
            "--store", ".assay/store",
            "--output", ".assay/runs",
            "--workers", String.valueOf(args.integer("workers")),
            "--timeout", String.valueOf(args.decimal("timeout"))
        });
        if (args.flag("no-serve")) {
            return code;
        }
        System.out.println("Opening the report dashboard. Press Ctrl+C to stop.");
        return AssayCli.run(new String[] {"serve", "--output", ".assay/runs", "--open-browser"});
    }

    private static String ask(String label, String fallback) {
        System.out.print(label + (fallback.isEmpty() ? "" : " [" + fallback + "]") + ": ");
        System.out.flush();
        try {
            String line = INPUT.readLine();
            return line == null || line.isBlank() ? fallback : line.trim();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
