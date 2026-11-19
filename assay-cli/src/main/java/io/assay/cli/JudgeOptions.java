package io.assay.cli;

import io.assay.llm.OpenAiCompatibleJudge;
import io.assay.metrics.LlmJudge;

/**
 * Resolves the judge used by metric and generation commands.
 *
 * <p>Environment configuration remains the default. {@code --base-url} and {@code --model} exist so
 * a run can point at a local OpenAI-compatible endpoint (a stub or gateway) without exporting
 * process-wide variables; the API key still comes from {@code ASSAY_API_KEY}.
 */
final class JudgeOptions {

    private static final double DEFAULT_TIMEOUT_SECONDS = 60;

    private JudgeOptions() {
    }

    static LlmJudge resolve(CommandLine.Namespace args) {
        String baseUrl = args.string("base-url");
        String model = args.string("model");
        boolean hasBaseUrl = baseUrl != null && !baseUrl.isBlank();
        boolean hasModel = model != null && !model.isBlank();
        if (hasBaseUrl != hasModel) {
            throw new CommandLine.UsageException("--base-url and --model must be used together", 2, false);
        }
        if (hasBaseUrl) {
            return OpenAiCompatibleJudge.from(
                    trimTrailingSlash(baseUrl), env("ASSAY_API_KEY"), model, DEFAULT_TIMEOUT_SECONDS);
        }
        return OpenAiCompatibleJudge.fromEnvironment();
    }

    private static String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }
}
