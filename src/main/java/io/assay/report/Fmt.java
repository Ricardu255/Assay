package io.assay.report;

import java.util.List;
import java.util.Locale;

/**
 * The number formats the reports render with.
 *
 * <p>Reports are read by people, so the exact rendering is part of the output contract: one decimal
 * place on rates and deltas, and an explicit sign wherever a value can move in either direction.
 */
public final class Fmt {

    private Fmt() {
    }

    /** A rate as a percentage with one decimal place. */
    public static String percent1(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    /** A rate delta as a signed percentage with one decimal place. */
    public static String signedPercent1(double value) {
        return String.format(Locale.ROOT, "%+.1f%%", value * 100);
    }

    /** An integer delta with an explicit sign. */
    public static String signedInt(int value) {
        return String.format(Locale.ROOT, "%+d", value);
    }

    /** A decimal delta with an explicit sign and one decimal place. */
    public static String signedFixed1(double value) {
        return String.format(Locale.ROOT, "%+.1f", value);
    }

    /** Joins report lines, keeping a deliberately empty final line meaningful. */
    public static String join(List<String> lines) {
        return String.join("\n", lines);
    }
}
