package io.assay.cli;

import io.assay.json.Json;
import io.assay.metrics.LlmJudge;
import io.assay.synth.SourceDocument;
import io.assay.synth.SourceLoader;
import io.assay.synth.SynthesisReport;
import io.assay.synth.SynthesisSettings;
import io.assay.synth.Synthesizer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Implements the {@code synth} command. */
final class SynthCommand {

    private SynthCommand() {
    }

    static int run(CommandLine.Namespace args) {
        LlmJudge judge = JudgeOptions.resolve(args);
        if (judge == null) {
            throw new CommandLine.UsageException("synth requires ASSAY_MODEL", 1, false);
        }
        SynthesisSettings settings;
        List<SourceDocument> documents;
        try {
            settings = settings(args);
            documents = SourceLoader.load(args.string("from"), args.path("input"));
        } catch (IllegalArgumentException error) {
            throw new CommandLine.UsageException(error.getMessage(), 2, false);
        }
        SynthesisReport report = new Synthesizer(judge, settings).synthesize(documents);
        Path out = args.path("out");
        int written = report.write(out);
        Map<String, Object> summary = new LinkedHashMap<>(report.toJson());
        summary.put("out", out.toString());
        summary.put("written", written);
        System.out.println(Json.write(summary));
        return written == 0 ? 1 : 0;
    }

    private static SynthesisSettings settings(CommandLine.Namespace args) {
        return new SynthesisSettings(
                args.string("scenario"),
                args.string("expected-key"),
                args.integer("contexts-per-document"),
                args.integer("per-context"),
                args.integer("evolutions"),
                args.integer("seed"),
                new SynthesisSettings.Limits(
                        args.integer("max-cases"), args.integer("max-calls"), args.integer("max-tokens")),
                new SynthesisSettings.Quality(
                        args.decimal("quality-threshold"),
                        args.integer("max-rewrite-retries"),
                        args.decimal("duplicate-threshold")),
                new SynthesisSettings.Chunking(
                        args.integer("chunk-chars"), args.integer("chunk-overlap")));
    }
}
