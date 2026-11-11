package io.assay.cli;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Short, CLI-friendly identifiers. */
final class CliIds {

    private CliIds() {
    }

    static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** Stable 12-hex-character id derived from an idempotency key, not from wall-clock state. */
    static String stableId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 12);
    }
}
