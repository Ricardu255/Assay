package io.assay.cli;

import io.assay.engine.CaseLoader;
import io.assay.json.Json;
import io.assay.metrics.LlmJudge;
import io.assay.model.EvalCase;
import io.assay.synth.AdversarialGenerator;
import io.assay.synth.AttackCategory;
import io.assay.synth.AttackSettings;
import io.assay.synth.SynthesisReport;
import io.assay.synth.SynthesisSettings;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Implements the {@code attack} command. */
final class AttackCommand {

    private AttackCommand() {
    }

    static int run(CommandLine.Namespace args) {
        LlmJudge judge = JudgeOptions.resolve(args);
        if (judge == null) {
            throw new CommandLine.UsageException("attack requires ASSAY_MODEL", 1, false);
        }
        AttackSettings settings;
        List<EvalCase> cases;
        try {
            settings = settings(args);
            cases = CaseLoader.loadCases(args.path("input"), "adversarial");
        } catch (IllegalArgumentException error) {
            throw new CommandLine.UsageException(error.getMessage(), 2, false);
        }
        SynthesisReport report = new AdversarialGenerator(judge, settings).generate(cases);
        Path out = args.path("out");
        int written = report.write(out);
        Map<String, Object> summary = new LinkedHashMap<>(report.toJson());
        summary.put("out", out.toString());
        summary.put("written", written);
        System.out.println(Json.write(summary));
        return written == 0 ? 1 : 0;
    }

    private static AttackSettings settings(CommandLine.Namespace args) {
        return new AttackSettings(
                AttackCategory.parse(args.string("categories")),
                args.string("scenario"),
                args.integer("variants"),
                new SynthesisSettings.Limits(
                        args.integer("max-cases"), args.integer("max-calls"), args.integer("max-tokens")),
                args.decimal("duplicate-threshold"));
    }
}
