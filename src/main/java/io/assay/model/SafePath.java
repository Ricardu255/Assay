package io.assay.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Validation of the safe forward-slash relative paths the framework accepts inside a sandbox.
 *
 * <p>The rules are: no backslashes, no absolute paths, no {@code ..} segments, and a usable final name.
 * Empty and {@code .} segments are dropped before the name is inspected, so {@code a/./b} and
 * {@code a//b} both resolve to {@code a/b}.
 */
public final class SafePath {

    private SafePath() {
    }

    /** Returns the normalised segment list, or throws when the value is not a safe relative path. */
    public static List<String> segments(String value) {
        List<String> segments = new ArrayList<>();
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            segments.add(segment);
        }
        return segments;
    }

    public static void require(String field, String value) {
        if (value == null) {
            return;
        }
        if (value.isBlank() || value.contains("\\")) {
            throw new IllegalArgumentException(field + " must be a safe forward-slash relative file path");
        }
        if (value.startsWith("/")) {
            throw new IllegalArgumentException(field + " must be a safe forward-slash relative file path");
        }
        List<String> segments = segments(value);
        if (segments.contains("..")) {
            throw new IllegalArgumentException(field + " must be a safe forward-slash relative file path");
        }
        String name = segments.isEmpty() ? "" : segments.get(segments.size() - 1);
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException(field + " must be a safe forward-slash relative file path");
        }
    }

    /** The normalised relative path, as {@code PurePosixPath.as_posix()} would render it. */
    public static String normalize(String value) {
        return String.join("/", segments(value));
    }
}
