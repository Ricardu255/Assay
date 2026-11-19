package io.assay.metrics;

import io.assay.json.Json;
import io.assay.model.NormalizedTrace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Builds the content-addressed key for one metric result.
 *
 * <p>The key covers the case, every trace collected for it, and the metric's configuration
 * fingerprint. Including the traces is deliberate: deepeval's cache key omits tool calls and trace
 * content, which makes agent metrics reuse results they should not.
 */
public final class MetricCacheKey {

    private MetricCacheKey() {
    }

    /** Returns the key, or {@code null} when the metric opts out of caching. */
    public static String of(MetricContext context, Metric metric) {
        String fingerprint = metric.fingerprint();
        if (fingerprint == null || fingerprint.isBlank()) {
            return null;
        }
        List<Object> traces = new ArrayList<>();
        for (NormalizedTrace trace : context.traces()) {
            traces.add(trace == null ? null : trace.toJson());
        }
        String material = metric.name() + '\n'
                + fingerprint + '\n'
                + Json.canonical(context.evalCase().toJson()) + '\n'
                + Json.canonical(traces);
        return sha256(material);
    }

    /** SHA-256 hex digest of the material. */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
