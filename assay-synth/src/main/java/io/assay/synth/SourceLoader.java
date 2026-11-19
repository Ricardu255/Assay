package io.assay.synth;

import io.assay.json.Json;
import io.assay.metrics.TraceRenderer;
import io.assay.model.NormalizedTrace;
import io.assay.support.AtomicFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Loads synthesis sources from documents or stored traces.
 *
 * <p>Documents are plain text or Markdown files. Traces are JSONL lines in the normalized trace
 * contract, rendered with the same evidence block a judge sees, so generated cases stay grounded in
 * what the agent actually did.
 */
public final class SourceLoader {

    private static final List<String> TEXT_SUFFIXES = List.of(".md", ".markdown", ".txt");

    private SourceLoader() {
    }

    /** Loads sources. {@code kind} is {@code docs} or {@code traces}. */
    public static List<SourceDocument> load(String kind, Path input) {
        if (input == null || !Files.exists(input)) {
            throw new IllegalArgumentException("input does not exist: " + input);
        }
        String normalized = kind == null ? "docs" : kind.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "docs" -> loadDocuments(input);
            case "traces" -> loadTraces(input);
            default -> throw new IllegalArgumentException("input type must be docs or traces: " + kind);
        };
    }

    private static List<SourceDocument> loadDocuments(Path input) {
        List<Path> files = files(input, path -> TEXT_SUFFIXES.stream()
                .anyMatch(suffix -> fileName(path).toLowerCase(Locale.ROOT).endsWith(suffix)));
        List<SourceDocument> documents = new ArrayList<>(files.size());
        for (Path file : files) {
            String text = AtomicFiles.readText(file);
            if (!text.isBlank()) {
                documents.add(new SourceDocument(fileName(file), text));
            }
        }
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("no .md or .txt documents found under " + input);
        }
        return documents;
    }

    private static List<SourceDocument> loadTraces(Path input) {
        List<Path> files = files(input, path -> fileName(path).toLowerCase(Locale.ROOT)
                .endsWith(".jsonl"));
        List<SourceDocument> documents = new ArrayList<>();
        for (Path file : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index).trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    NormalizedTrace trace = NormalizedTrace.fromDict(Json.parseObject(line));
                    String rendered = TraceRenderer.render(null, trace);
                    documents.add(new SourceDocument(fileName(file) + "#" + (index + 1), rendered));
                } catch (RuntimeException error) {
                    throw new IllegalArgumentException(
                            "cannot read trace " + fileName(file) + ":" + (index + 1) + ": "
                                    + error.getMessage(), error);
                }
            }
        }
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("no trace records found under " + input);
        }
        return documents;
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    private static List<Path> files(Path input, java.util.function.Predicate<Path> filter) {
        if (Files.isRegularFile(input)) {
            return filter.test(input) ? List.of(input) : List.of();
        }
        try (Stream<Path> walk = Files.walk(input, 8)) {
            return walk.filter(Files::isRegularFile).filter(filter)
                    .sorted(Comparator.comparing(Path::toString)).toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
