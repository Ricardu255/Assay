package io.assay.synth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Rejects near-duplicate questions using word shingles.
 *
 * <p>deepeval's synthesizer has no case-level deduplication at all, so a long document can produce
 * the same question repeatedly. The threshold is a Jaccard similarity over 3-word shingles.
 */
public final class CaseDeduplicator {

    private final double threshold;
    private final List<Set<String>> seen = new ArrayList<>();

    public CaseDeduplicator(double threshold) {
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("duplicate threshold must be between 0 and 1");
        }
        this.threshold = threshold;
    }

    /** Returns true when {@code text} is a near-duplicate of an accepted case. */
    public boolean isDuplicate(String text) {
        Set<String> shingles = shingles(text);
        for (Set<String> previous : seen) {
            if (jaccard(previous, shingles) >= threshold) {
                return true;
            }
        }
        seen.add(shingles);
        return false;
    }

    private static Set<String> shingles(String text) {
        List<String> words = new ArrayList<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isBlank()) {
                words.add(word);
            }
        }
        Set<String> shingles = new HashSet<>();
        if (words.size() < 3) {
            shingles.add(String.join(" ", words));
            return shingles;
        }
        for (int index = 0; index + 2 < words.size(); index++) {
            shingles.add(words.get(index) + ' ' + words.get(index + 1) + ' ' + words.get(index + 2));
        }
        return shingles;
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }
}
