package io.assay.evolve;

import io.assay.metrics.LlmJudge;
import io.assay.model.AutoEvolutionAdapter;
import io.assay.model.EvolutionDiagnosis;
import io.assay.model.TextCandidate;
import io.assay.model.Values;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A feedback-driven candidate generator for {@code evolve-auto}.
 *
 * <p>GEPA and COPRO differ mainly in breadth: GEPA proposes a small set of focused edits per round,
 * COPRO proposes a wider set and filters near-duplicates. Both ask the judge for complete replacement
 * artifacts, keep the loop's accept/reject decision outside the model, and return at most the
 * configured breadth. The generator never stages or applies a candidate itself.
 */
public final class PromptOptimizer implements AutoEvolutionAdapter.CandidateGenerator {

    /** Candidate breadth strategy. */
    public enum Algorithm {
        GEPA,
        COPRO
    }

    private static final int MAX_CURRENT_CHARS = 8_000;
    private static final int MAX_CANDIDATE_CHARS = 20_000;

    private final LlmJudge judge;
    private final Algorithm algorithm;
    private final int breadth;
    private final double similarityThreshold;

    public PromptOptimizer(LlmJudge judge, Algorithm algorithm, int breadth, double similarityThreshold) {
        if (judge == null) {
            throw new IllegalArgumentException("prompt optimizer requires an LLM judge");
        }
        if (breadth < 1 || breadth > 10) {
            throw new IllegalArgumentException("breadth must be between 1 and 10");
        }
        if (!Double.isFinite(similarityThreshold) || similarityThreshold < 0 || similarityThreshold > 1) {
            throw new IllegalArgumentException("similarity threshold must be between 0 and 1");
        }
        this.judge = judge;
        this.algorithm = algorithm == null ? Algorithm.GEPA : algorithm;
        this.breadth = breadth;
        this.similarityThreshold = similarityThreshold;
    }

    /** GEPA defaults: two focused candidates per round. */
    public static PromptOptimizer gepa(LlmJudge judge) {
        return new PromptOptimizer(judge, Algorithm.GEPA, 2, 0.9);
    }

    /** COPRO defaults: four candidates per round, deduplicated. */
    public static PromptOptimizer copro(LlmJudge judge) {
        return new PromptOptimizer(judge, Algorithm.COPRO, 4, 0.9);
    }

    @Override
    public List<TextCandidate> generate(
            EvolutionDiagnosis diagnosis, Object currentContent, int roundNumber) {
        String current = text(currentContent);
        if (current.isBlank()) {
            return List.of();
        }
        LlmJudge.Response response = judge.evaluate(prompt(diagnosis, current, roundNumber));
        return candidates(response, current, roundNumber);
    }

    private String prompt(EvolutionDiagnosis diagnosis, String current, int roundNumber) {
        String evidence = diagnosis.evidenceCaseIds().isEmpty()
                ? "none"
                : String.join(", ", diagnosis.evidenceCaseIds());
        return "You are improving a text artifact used by an AI agent.\n"
                + "Algorithm: " + algorithm.name().toLowerCase(Locale.ROOT) + "\n"
                + "Round: " + roundNumber + "\n"
                + "Diagnosis: " + diagnosis.summary() + "\n"
                + "Evidence case ids: " + evidence + "\n"
                + "Suspected modules: " + String.join(", ", diagnosis.suspectedModules()) + "\n"
                + "Constraints: " + String.join(", ", diagnosis.constraints()) + "\n\n"
                + "Current artifact:\n" + truncate(current, MAX_CURRENT_CHARS) + "\n\n"
                + "Propose " + breadth + " distinct complete replacement artifact revisions. "
                + "Each revision must be a full artifact, not a diff, must preserve the artifact's "
                + "format, and must address the diagnosis without touching evaluation data.\n"
                + "Return JSON only: {\"candidates\":[{\"focus\":\"...\",\"content\":\"...\"}]}";
    }

    private List<TextCandidate> candidates(LlmJudge.Response response, String current, int roundNumber) {
        List<TextCandidate> accepted = new ArrayList<>();
        List<String> acceptedContent = new ArrayList<>();
        for (Object raw : Values.asList(response.result().get("candidates"))) {
            Map<String, Object> item = Values.asMap(raw);
            String content = text(item.get("content"));
            if (content.isBlank() || content.length() > MAX_CANDIDATE_CHARS) {
                continue;
            }
            if (tooSimilar(current, content, acceptedContent)) {
                continue;
            }
            String focus = text(item.get("focus"));
            int index = accepted.size() + 1;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("optimizer", algorithm.name().toLowerCase(Locale.ROOT));
            metadata.put("focus", focus);
            metadata.put("round", roundNumber);
            metadata.put("model", judge.model());
            metadata.put("input_tokens", response.inputTokens());
            metadata.put("output_tokens", response.outputTokens());
            accepted.add(new TextCandidate(
                    "po-r" + roundNumber + "-" + index,
                    "po-" + roundNumber + "-" + index,
                    content,
                    focus.isEmpty() ? "round " + roundNumber + " candidate " + index : focus,
                    "skill",
                    metadata,
                    Map.of(),
                    List.of()));
            acceptedContent.add(content);
            if (accepted.size() == breadth) {
                break;
            }
        }
        return accepted;
    }

    private boolean tooSimilar(String current, String candidate, List<String> accepted) {
        if (normalize(current).equals(normalize(candidate))) {
            return true;
        }
        for (String previous : accepted) {
            if (similarity(previous, candidate) >= similarityThreshold) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }

    private static double similarity(String left, String right) {
        Set<String> a = trigrams(left);
        Set<String> b = trigrams(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> trigrams(String text) {
        List<String> words = words(text);
        Set<String> grams = new HashSet<>();
        if (words.size() < 3) {
            grams.add(String.join(" ", words));
            return grams;
        }
        for (int index = 0; index + 2 < words.size(); index++) {
            grams.add(words.get(index) + ' ' + words.get(index + 1) + ' ' + words.get(index + 2));
        }
        return grams;
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isBlank()) {
                words.add(word);
            }
        }
        return words;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
