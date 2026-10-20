package io.assay.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Defensive copies that preserve insertion order and tolerate nulls.
 *
 * <p>{@code Map.copyOf}/{@code List.copyOf} would be the obvious choice, but they reject null values
 * and — for maps — do not promise iteration order. Both matter here: JSON documents routinely carry
 * explicit nulls, and the order of the fields a model writes is the order a reader sees.
 */
public final class Immutable {

    private Immutable() {
    }

    public static <K, V> Map<K, V> map(Map<K, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public static <T> List<T> list(List<T> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
