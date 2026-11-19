package io.assay.synth;

import io.assay.metrics.LlmJudge;
import io.assay.model.Values;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Generates grounded evaluation cases from documents or traces.
 *
 * <p>Every candidate goes through three gates before it is written: an LLM quality score with bounded
 * rewrites, near-duplicate rejection, and the run's case/call/token limits. A candidate that still
 * scores below the threshold is discarded and counted, never kept as a low-quality case.
 */
public final class Synthesizer {

    private static final int MAX_INPUT_CHARS = 2_000;
    private static final int MAX_EXPECTED_CHARS = 4_000;
    private static final int CONTEXT_EXCERPT_CHARS = 400;

    private final LlmJudge judge;
    private final SynthesisSettings settings;
    private final Random random;
    private final CaseDeduplicator deduplicator;
    private int calls;
    private long inputTokens;
    private long outputTokens;
    private boolean budgetExhausted;
    private int caseCounter;
    private int duplicates;
    private int discarded;

    public Synthesizer(LlmJudge judge, SynthesisSettings settings) {
        if (judge == null) {
            throw new IllegalArgumentException("synthesis requires an LLM; set ASSAY_MODEL");
        }
        this.judge = judge;
        this.settings = settings == null ? SynthesisSettings.defaults() : settings;
        this.random = new Random(this.settings.seed());
        this.deduplicator = new CaseDeduplicator(this.settings.quality().duplicateThreshold());
    }

    /** Runs synthesis over the supplied sources. */
    public SynthesisReport synthesize(List<SourceDocument> documents) {
        List<SyntheticCase> cases = new ArrayList<>();
        ContextChunker chunker = new ContextChunker(
                settings.chunking().chars(), settings.chunking().overlapChars());
        outer:
        for (SourceDocument document : documents) {
            List<ContextChunk> chunks = chunker.split(document);
            int contexts = Math.min(settings.contextsPerDocument(), chunks.size());
            for (int index = 0; index < contexts; index++) {
                if (budgetExhausted) {
                    break outer;
                }
                ContextChunk chunk = chunks.get(index);
                for (Generated generated : generate(chunk)) {
                    if (budgetExhausted || cases.size() >= settings.limits().maxCases()) {
                        break outer;
                    }
                    Filtered filtered = filter(chunk, generated);
                    if (filtered == null || filtered.quality() < settings.quality().threshold()) {
                        discarded++;
                        continue;
                    }
                    Evolved evolved = evolve(chunk, filtered.generated());
                    if (deduplicator.isDuplicate(evolved.input())) {
                        duplicates++;
                        continue;
                    }
                    cases.add(caseOf(evolved, filtered.quality(), document.name(), chunk.text()));
                }
            }
        }
        return new SynthesisReport(
                cases, duplicates, discarded, calls, inputTokens, outputTokens, budgetExhausted);
    }

    private List<Generated> generate(ContextChunk chunk) {
        int wanted = settings.casesPerContext();
        String prompt = "You are creating evaluation cases from a source document.\n"
                + "Source: " + chunk.source() + "\n"
                + "Context:\n" + chunk.text() + "\n\n"
                + "Write up to " + wanted + " realistic user requests that are fully answerable from the "
                + "context. For each request include a concise expected answer grounded only in the context.\n"
                + "Return JSON only: {\"cases\":[{\"input\":\"...\",\"expected_output\":\"...\"}]}\n"
                + "Do not use facts outside the context.";
        Map<String, Object> result = call(prompt);
        if (result == null) {
            return List.of();
        }
        Object raw = result.containsKey("cases") ? result.get("cases") : result.get("data");
        List<Generated> generated = new ArrayList<>();
        for (Object row : Values.asList(raw)) {
            Map<String, Object> item = Values.asMap(row);
            String input = text(item.get("input"));
            String expected = text(item.containsKey("expected_output")
                    ? item.get("expected_output")
                    : item.get("expected"));
            if (!input.isEmpty() && !expected.isEmpty() && generated.size() < wanted) {
                generated.add(new Generated(trim(input, MAX_INPUT_CHARS), trim(expected, MAX_EXPECTED_CHARS)));
            }
        }
        return generated;
    }

    private Filtered filter(ContextChunk chunk, Generated generated) {
        Quality quality = quality(chunk, generated);
        int retries = 0;
        while (quality.score() >= 0
                && quality.score() < settings.quality().threshold()
                && retries < settings.quality().maxRewriteRetries()
                && !budgetExhausted) {
            String rewritten = rewrite(chunk, generated, quality.feedback());
            if (rewritten != null && !rewritten.isBlank()) {
                generated = new Generated(trim(rewritten, MAX_INPUT_CHARS), generated.expected());
            }
            retries++;
            quality = quality(chunk, generated);
        }
        return quality.score() < 0 ? null : new Filtered(generated, quality.score());
    }

    private Quality quality(ContextChunk chunk, Generated generated) {
        String prompt = "Evaluate this synthetic evaluation case.\n"
                + "Context:\n" + chunk.text() + "\n\n"
                + "Question:\n" + generated.input() + "\n\n"
                + "Expected answer:\n" + generated.expected() + "\n\n"
                + "Score 0 to 10 on three points: the question is self-contained, the objective is clear, "
                + "and the expected answer is fully supported by the context.\n"
                + "Return JSON only: {\"score\": <number 0-10>, \"feedback\": \"...\"}";
        Map<String, Object> result = call(prompt);
        if (result == null) {
            return new Quality(-1, "");
        }
        Double score = score(result.get("score"));
        return new Quality(score == null ? -1 : score, text(result.get("feedback")));
    }

    private String rewrite(ContextChunk chunk, Generated generated, String feedback) {
        String prompt = "Rewrite this evaluation question so it is self-contained and its objective is clear.\n"
                + "Context:\n" + chunk.text() + "\n\n"
                + "Question:\n" + generated.input() + "\n\n"
                + "Reviewer feedback:\n" + feedback + "\n\n"
                + "Keep the question answerable from the context and keep the same intent. "
                + "Return JSON only: {\"input\": \"...\"}";
        Map<String, Object> result = call(prompt);
        return result == null ? null : text(result.get("input"));
    }

    private Evolved evolve(ContextChunk chunk, Generated generated) {
        String input = generated.input();
        List<String> applied = new ArrayList<>();
        EvolutionStrategy[] strategies = EvolutionStrategy.values();
        for (int round = 0; round < settings.evolutions() && !budgetExhausted; round++) {
            EvolutionStrategy strategy = strategies[random.nextInt(strategies.length)];
            String prompt = "Rewrite this evaluation question to " + strategy.instruction() + ".\n"
                    + "Context:\n" + chunk.text() + "\n\n"
                    + "Question:\n" + input + "\n\n"
                    + "The answer must still be fully supported by the context. Do not mention the context. "
                    + "Keep it under 40 words.\n"
                    + "Return JSON only: {\"input\": \"...\"}";
            Map<String, Object> result = call(prompt);
            String rewritten = result == null ? "" : text(result.get("input"));
            if (!rewritten.isEmpty()) {
                input = trim(rewritten, MAX_INPUT_CHARS);
                applied.add(strategy.name().toLowerCase(Locale.ROOT));
            }
        }
        return new Evolved(input, generated.expected(), applied);
    }

    private SyntheticCase caseOf(Evolved evolved, double quality, String source, String context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "synthetic");
        metadata.put("source_file", source);
        metadata.put("quality", Math.round(quality * 10d) / 10d);
        metadata.put("evolutions", evolved.evolutions());
        metadata.put("seed", settings.seed());
        metadata.put("context_excerpt", excerpt(context));
        caseCounter++;
        return new SyntheticCase(
                String.format(Locale.ROOT, "synth-%04d", caseCounter),
                evolved.input(),
                settings.expectedKey(),
                evolved.expected(),
                settings.scenario(),
                metadata);
    }

    private Map<String, Object> call(String prompt) {
        int maxCalls = settings.limits().maxCalls();
        if (budgetExhausted || calls >= maxCalls) {
            budgetExhausted = true;
            return null;
        }
        LlmJudge.Response response;
        try {
            response = judge.evaluate(prompt);
        } catch (RuntimeException error) {
            throw new IllegalStateException("synthesis judge failed: " + messageOf(error), error);
        }
        calls++;
        inputTokens += response.inputTokens();
        outputTokens += response.outputTokens();
        long maxTokens = settings.limits().maxTokens();
        if (maxTokens > 0 && inputTokens + outputTokens >= maxTokens) {
            budgetExhausted = true;
        }
        return response.result();
    }

    private static Double score(Object value) {
        double score;
        if (value instanceof Number number) {
            score = number.doubleValue();
        } else if (value instanceof String text) {
            try {
                score = Double.parseDouble(text.trim());
            } catch (NumberFormatException error) {
                return null;
            }
        } else {
            return null;
        }
        return Double.isFinite(score) && score >= 0 && score <= 10 ? score : null;
    }

    private static String excerpt(String input) {
        return input.length() <= CONTEXT_EXCERPT_CHARS ? input : input.substring(0, CONTEXT_EXCERPT_CHARS);
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String messageOf(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record Generated(String input, String expected) {
    }

    private record Quality(double score, String feedback) {
    }

    private record Filtered(Generated generated, double quality) {
    }

    private record Evolved(String input, String expected, List<String> evolutions) {
    }

    /** Evolution styles; the prompt constraint that keeps each one grounded is shared. */
    private enum EvolutionStrategy {
        REASONING("require multi-step reasoning"),
        CONCRETIZING("add concrete details from the context"),
        CONSTRAINED("add one explicit constraint"),
        COMPARATIVE("ask for a comparison"),
        HYPOTHETICAL("ask a what-if question"),
        IN_BREADTH("broaden the scenario");

        private final String instruction;

        EvolutionStrategy(String instruction) {
            this.instruction = instruction;
        }

        String instruction() {
            return instruction;
        }
    }
}
