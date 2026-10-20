package io.assay.support;

import java.nio.file.Path;

/** Name validation shared by every component that turns an identifier into a path segment. */
public final class Names {

    private Names() {
    }

    /**
     * The value must be non-empty and must not contain a path separator, so it is usable as a single
     * directory or file name.
     */
    public static String safeName(String value, String field) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()
                || trimmed.equals(".")
                || trimmed.equals("..")
                || trimmed.contains("/")
                || trimmed.contains("\\")
                || !Path.of(trimmed).getFileName().toString().equals(trimmed)) {
            throw new IllegalArgumentException(field + " must be a safe non-empty name");
        }
        return trimmed;
    }
}
