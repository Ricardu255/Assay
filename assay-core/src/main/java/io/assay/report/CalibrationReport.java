package io.assay.report;

import io.assay.model.Values;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compares human review decisions with the automatic verdict.
 *
 * <p>This is the evidence that answers "does the evaluator agree with a person?". A metric that looks
 * precise but flags good cases (false positives) wastes review time; one that misses bad cases (false
 * negatives) lets regressions through. Both are counted separately instead of being hidden in a
 * single accuracy number.
 */
public final class CalibrationReport {

    private static final Set<String> HUMAN_BAD = Set.of(
            "confirmed_badcase", "confirmed_bad", "badcase", "incorrect", "fail", "failed", "rejected");
    private static final Set<String> HUMAN_GOOD = Set.of(
            "accepted", "correct", "pass", "passed", "good", "confirmed_good", "confirmed_goodcase",
            "false_positive");

    private CalibrationReport() {
    }

    /** Computes the confusion matrix; {@code metric} is null for the hard-pass verdict. */
    public static Map<String, Object> compute(List<Map<String, Object>> results, String metric) {
        int truePositive = 0;
        int falsePositive = 0;
        int falseNegative = 0;
        int trueNegative = 0;
        int unrecognized = 0;
        int notApplicable = 0;
        List<Map<String, Object>> rows = results == null ? List.of() : results;
        for (Map<String, Object> result : rows) {
            Verdict human = humanVerdict(Values.asMap(result).get("human_decision"));
            if (human == Verdict.UNKNOWN) {
                unrecognized++;
                continue;
            }
            Verdict auto = autoVerdict(result, metric);
            if (auto == Verdict.UNKNOWN) {
                notApplicable++;
                continue;
            }
            if (human == Verdict.BAD && auto == Verdict.BAD) {
                truePositive++;
            } else if (human == Verdict.BAD) {
                falseNegative++;
            } else if (auto == Verdict.BAD) {
                falsePositive++;
            } else {
                trueNegative++;
            }
        }
        int recognized = truePositive + falsePositive + falseNegative + trueNegative;
        Map<String, Object> matrix = new LinkedHashMap<>();
        matrix.put("true_positive", truePositive);
        matrix.put("false_positive", falsePositive);
        matrix.put("false_negative", falseNegative);
        matrix.put("true_negative", trueNegative);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("verdict", metric == null || metric.isBlank() ? "hard_pass" : "metric:" + metric);
        report.put("samples", rows.size());
        report.put("recognized", recognized);
        report.put("unrecognized_decision", unrecognized);
        report.put("not_applicable", notApplicable);
        report.put("matrix", matrix);
        report.put("precision", ratio(truePositive, truePositive + falsePositive));
        report.put("recall", ratio(truePositive, truePositive + falseNegative));
        report.put("agreement", ratio(truePositive + trueNegative, recognized));
        Double precision = (Double) report.get("precision");
        Double recall = (Double) report.get("recall");
        report.put(
                "f1",
                precision == null || recall == null || precision + recall == 0
                        ? null
                        : 2 * precision * recall / (precision + recall));
        return report;
    }

    /** Renders the calibration as Markdown for the review record. */
    public static String markdown(Map<String, Object> report) {
        Map<String, Object> matrix = Values.asMap(report.get("matrix"));
        StringBuilder text = new StringBuilder();
        text.append("# Evaluator Calibration\n\n");
        text.append("- Automatic verdict: `").append(report.get("verdict")).append("`\n");
        text.append("- Reviewed samples: ").append(report.get("samples"))
                .append(" (recognized ").append(report.get("recognized"))
                .append(", unrecognized ").append(report.get("unrecognized_decision"))
                .append(", not applicable ").append(report.get("not_applicable")).append(")\n\n");
        text.append("| | Human: bad | Human: good |\n");
        text.append("|---|---:|---:|\n");
        text.append("| Auto: bad | ").append(matrix.get("true_positive"))
                .append(" | ").append(matrix.get("false_positive")).append(" |\n");
        text.append("| Auto: good | ").append(matrix.get("false_negative"))
                .append(" | ").append(matrix.get("true_negative")).append(" |\n\n");
        text.append("| Precision | Recall | F1 | Agreement |\n");
        text.append("|---:|---:|---:|---:|\n");
        text.append("| ").append(percent(report.get("precision")))
                .append(" | ").append(percent(report.get("recall")))
                .append(" | ").append(percent(report.get("f1")))
                .append(" | ").append(percent(report.get("agreement"))).append(" |\n\n");
        text.append("> Precision counts flagged cases a person also called bad; recall counts bad cases ")
                .append("the evaluator caught. Small samples produce wide uncertainty — treat these as a ")
                .append("calibration signal, not a statistical guarantee.\n");
        return text.toString();
    }

    private enum Verdict {
        BAD,
        GOOD,
        UNKNOWN
    }

    private static Verdict humanVerdict(Object decision) {
        String value = decision == null ? "" : String.valueOf(decision).trim().toLowerCase(Locale.ROOT);
        if (HUMAN_BAD.contains(value)) {
            return Verdict.BAD;
        }
        if (HUMAN_GOOD.contains(value)) {
            return Verdict.GOOD;
        }
        return Verdict.UNKNOWN;
    }

    private static Verdict autoVerdict(Map<String, Object> result, String metric) {
        if (metric == null || metric.isBlank()) {
            return Boolean.TRUE.equals(result.get("hard_pass")) ? Verdict.GOOD : Verdict.BAD;
        }
        for (Object raw : Values.asList(result.get("metrics"))) {
            Map<String, Object> row = Values.asMap(raw);
            if (!metric.equals(String.valueOf(row.get("name")))) {
                continue;
            }
            String status = String.valueOf(row.get("status")).toLowerCase(Locale.ROOT);
            if ("fail".equals(status)) {
                return Verdict.BAD;
            }
            if ("pass".equals(status)) {
                return Verdict.GOOD;
            }
            return Verdict.UNKNOWN;
        }
        return Verdict.UNKNOWN;
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static String percent(Object value) {
        return value instanceof Number number ? Fmt.percent1(number.doubleValue()) : "—";
    }
}
