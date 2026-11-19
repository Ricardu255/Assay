package io.assay.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Removes credentials from a trace before it is persisted or sent to a model.
 *
 * <p>It masks credential-shaped keys and common personal identifiers (email, mainland China mobile
 * number, 18-digit resident ID) in stored evidence and model input. The patterns are intentionally
 * conservative: an order number that merely looks numeric is not treated as an ID. Organizations with
 * additional PII classes should still redact at ingest, where they know the field semantics.
 */
public final class TraceRedactor {

    /** Placeholder written in place of a secret. */
    public static final String MASK = "***";

    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern API_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{12,}");
    private static final Pattern EMAIL =
            Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern PHONE_CN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CN = Pattern.compile(
            "(?<![0-9A-Za-z])[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])"
                    + "(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx](?![0-9A-Za-z])");
    private static final List<String> SECRET_KEYS = List.of(
            "authorization", "api_key", "apikey", "access_token", "refresh_token",
            "secret", "password", "credential", "private_key");

    private TraceRedactor() {
    }

    /** Returns a copy of the trace with credential-shaped fields masked. */
    public static NormalizedTrace redact(NormalizedTrace trace) {
        if (trace == null) {
            return null;
        }
        List<TraceEvent> events = new ArrayList<>(trace.events().size());
        for (TraceEvent event : trace.events()) {
            events.add(new TraceEvent(
                    event.module(),
                    event.action(),
                    event.status(),
                    event.durationMs(),
                    maskText(event.error()),
                    asMap(redactValue(event.fields()))));
        }
        return new NormalizedTrace(
                trace.traceId(),
                redactValue(trace.finalOutput()),
                events,
                asMap(redactValue(trace.fields())),
                asMap(redactValue(trace.feedback())),
                trace.targetType(),
                trace.targetId(),
                trace.targetVersion(),
                asMap(redactValue(trace.raw())));
    }

    /** Recursively masks credential-shaped keys and strings in a JSON-shaped value. */
    public static Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                copy.put(key, isSecretKey(key) ? MASK : redactValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(redactValue(item));
            }
            return copy;
        }
        if (value instanceof String text) {
            return maskText(text);
        }
        return value;
    }

    /** Masks credentials and common personal identifiers inside a string. */
    public static String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = BEARER.matcher(text).replaceAll("Bearer " + MASK);
        masked = API_KEY.matcher(masked).replaceAll("sk-" + MASK);
        masked = EMAIL.matcher(masked).replaceAll(MASK);
        masked = PHONE_CN.matcher(masked).replaceAll(MASK);
        return ID_CN.matcher(masked).replaceAll(MASK);
    }

    private static boolean isSecretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        for (String secret : SECRET_KEYS) {
            if (normalized.equals(secret) || normalized.endsWith("_" + secret)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }
}
