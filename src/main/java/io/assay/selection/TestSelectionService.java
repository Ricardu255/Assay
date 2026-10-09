package io.assay.selection;

import io.assay.diff.DiffAnalysis;
import io.assay.diff.DiffSnapshot;
import io.assay.model.TestImpactAssessment;
import io.assay.model.TestSelection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chooses the test suites a change should trigger.
 *
 * <p>The deterministic rules always run; an optional {@link JsonReviewer} can raise the recommendation,
 * but never on its own authority.
 */
public final class TestSelectionService {

    private TestSelectionService() {
    }

    /**
     * The semantic hook the AI path calls.
     *
     * <p>The endpoint plus a single JSON round-trip. The reviewer is a candidate source only — it can
     * raise the mode but cannot lower or veto the rules.
     */
    public interface JsonReviewer {

        String baseUrl();

        Map<String, Object> requestJson(String prompt);
    }

    private static final Map<String, Integer> MODE_RANK =
            Map.of("smoke", 0, "regression", 1, "full", 2);

    private static final Map<String, List<String>> SUITES = Map.of(
            "smoke", List.of("smoke"),
            "regression", List.of("regression", "smoke"),
            "full", List.of("regression", "smoke", "full"));

    private static final String AI_PROMPT =
            "Assess the real test impact of this Git change. Return one JSON object with mode "
                    + "(smoke, regression, or full), confidence (0..1), risk (low, medium, or high), "
                    + "and reasons (an array of short strings). Smoke is for UI/report-only low-risk changes; "
                    + "full is for generation, RAG/retrieval, or evaluation-result schema changes; regression "
                    + "is for Planner, Prompt, safety, follow-up, quality logic, and other protected behavior.\n\n";

    /** Runs the deterministic rules over the change, optionally raising the mode with an AI assessment. */
    public static TestSelection selectTests(
            Path repository,
            String base,
            String aiProvider,
            String aiInput,
            JsonReviewer reviewer,
            double confidenceThreshold,
            DiffSnapshot snapshot) {
        if (!oneOf(aiProvider, "none", "local", "remote")) {
            throw new IllegalArgumentException("ai_provider must be none, local, or remote");
        }
        if (!oneOf(aiInput, "auto", "raw", "summary")) {
            throw new IllegalArgumentException("ai_input must be auto, raw, or summary");
        }
        if (!(confidenceThreshold >= 0 && confidenceThreshold <= 1)) {
            throw new IllegalArgumentException("confidence_threshold must be between 0 and 1");
        }

        DiffSnapshot effective = snapshot != null ? snapshot : DiffAnalysis.readGitDiff(repository, base);
        TestImpactAssessment rules = DiffAnalysis.assessRules(effective);
        TestImpactAssessment ai = null;
        List<String> reviewReasons = new ArrayList<>();

        if (!aiProvider.equals("none")) {
            if (reviewer == null) {
                throw new IllegalArgumentException("AI selection requires a reviewer");
            }
            if (aiProvider.equals("local") && !isPrivateEndpoint(reviewer.baseUrl())) {
                throw new IllegalArgumentException("local AI requires a loopback, private-IP, or .local endpoint");
            }
            if (aiProvider.equals("remote") && aiInput.equals("raw")) {
                throw new IllegalArgumentException("remote AI can only receive a sanitized summary");
            }
            String inputKind = (aiInput.equals("auto") && aiProvider.equals("local")) ? "raw"
                    : (aiInput.equals("auto") ? "summary" : aiInput);
            try {
                ai = aiAssessment(reviewer, effective, inputKind);
            } catch (RuntimeException error) {
                reviewReasons.add("AI assessment failed: " + error.getClass().getSimpleName());
            }
        }

        String finalMode = rules.mode();
        if (ai != null) {
            if (ai.confidence() < confidenceThreshold) {
                reviewReasons.add("AI confidence is below the configured threshold.");
            }
            if (!ai.mode().equals(rules.mode())) {
                reviewReasons.add("AI and deterministic rules recommend different modes.");
            }
            if (MODE_RANK.get(ai.mode()) > MODE_RANK.get(finalMode)) {
                finalMode = ai.mode();
            }
        }
        if (rules.risk().equals("high") || (ai != null && ai.risk().equals("high"))) {
            reviewReasons.add("The change is high risk.");
        }

        return new TestSelection(
                finalMode,
                SUITES.get(finalMode),
                rules,
                ai,
                !reviewReasons.isEmpty(),
                new ArrayList<>(new LinkedHashSet<>(reviewReasons)),
                effective.files().size(),
                effective.additions(),
                effective.deletions());
    }

    /** The all-defaults entry point: {@code base="HEAD"}, no AI, threshold 0.7, fresh snapshot. */
    public static TestSelection selectTests(Path repository) {
        return selectTests(repository, "HEAD", "none", "auto", null, 0.7, null);
    }

    private static TestImpactAssessment aiAssessment(
            JsonReviewer reviewer, DiffSnapshot snapshot, String inputKind) {
        String evidence = inputKind.equals("raw") ? snapshot.rawDiff() : snapshot.sanitizedSummary();
        String prompt = AI_PROMPT + evidence;
        Map<String, Object> value = reviewer.requestJson(prompt);
        String mode = textOf(value.containsKey("mode") ? value.get("mode") : "").toLowerCase(Locale.ROOT);
        double confidence = toDouble(value.containsKey("confidence") ? value.get("confidence") : 0L);
        String risk = textOf(value.containsKey("risk") ? value.get("risk") : "medium").toLowerCase(Locale.ROOT);
        List<String> reasons = reasonsOf(value.containsKey("reasons") ? value.get("reasons") : List.of());
        return new TestImpactAssessment(mode, confidence, risk, reasons, "ai", inputKind);
    }

    /**
     * Whether an endpoint resolves to a loopback, private, link-local or unspecified address, or a
     * {@code localhost}/{@code .local} name. A hostname that is not an IP literal is treated as public.
     */
    static boolean isPrivateEndpoint(String url) {
        String host = hostOf(url);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) {
            return true;
        }
        // Only an IP literal can be judged by address class; a plain name must not trigger a lookup.
        if (host.indexOf(':') < 0 && !isIpv4Literal(host)) {
            return false;
        }
        if (host.isEmpty()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isSiteLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress();
        } catch (UnknownHostException error) {
            return false;
        }
    }

    private static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        String host = null;
        try {
            host = new URI(url).getHost();
        } catch (URISyntaxException ignored) {
            // Fall through to the manual parse below.
        }
        if (host == null) {
            int marker = url.indexOf("://");
            if (marker < 0) {
                return "";
            }
            host = url.substring(marker + 3);
            for (int index = 0; index < host.length(); index++) {
                char character = host.charAt(index);
                if (character == '/' || character == '?' || character == '#') {
                    host = host.substring(0, index);
                    break;
                }
            }
            int at = host.lastIndexOf('@');
            if (at >= 0) {
                host = host.substring(at + 1);
            }
            if (host.startsWith("[")) {
                int close = host.indexOf(']');
                host = close >= 0 ? host.substring(1, close) : "";
            } else {
                int colon = host.indexOf(':');
                if (colon >= 0) {
                    host = host.substring(0, colon);
                }
            }
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private static boolean isIpv4Literal(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return false;
            }
            for (int index = 0; index < part.length(); index++) {
                char character = part.charAt(index);
                if (character < '0' || character > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean oneOf(String value, String... options) {
        for (String option : options) {
            if (option.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static String textOf(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Double || value instanceof Float) {
            return Double.toString(((Number) value).doubleValue());
        }
        if (value instanceof Number number) {
            return Long.toString(number.longValue());
        }
        return String.valueOf(value);
    }

    private static List<String> reasonsOf(Object value) {
        List<String> reasons = new ArrayList<>();
        if (value instanceof String text) {
            // A string is iterated as a sequence of one-character strings.
            for (int index = 0; index < text.length(); index++) {
                reasons.add(String.valueOf(text.charAt(index)));
            }
            return reasons;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                reasons.add(textOf(item));
            }
            return reasons;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                reasons.add(textOf(key));
            }
            return reasons;
        }
        throw new IllegalArgumentException("reasons must be a string or an array, not "
                + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private static double toDouble(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("confidence must be a number or a numeric string, not null");
        }
        if (value instanceof Boolean flag) {
            return flag ? 1.0 : 0.0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            String trimmed = text.strip();
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("confidence is not a number: " + textOf(text));
            }
        }
        throw new IllegalArgumentException(
                "confidence must be a number or a numeric string, not " + value.getClass().getSimpleName());
    }
}
