package io.assay.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The JSON codec used across the framework.
 *
 * <p>Written against the JDK alone, so the framework core carries no external dependency. The surface
 * is deliberately narrow: parse, default write, indented write, and a canonical form used for content
 * hashing.
 *
 * <p>Rendering happens in two steps — {@link #normalize} converts models into plain maps, lists, and
 * scalars, then the writer renders those. Keeping them apart matters: a single mixed pass would
 * re-normalize every container it produced and recurse forever.
 */
public final class Json {

    private Json() {
    }

    /** Reads a complete JSON document. */
    public static Object parse(String text) {
        return JsonParser.parse(text);
    }

    /** Reads a JSON object, rejecting any other top-level value. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    /**
     * Extracts the first JSON object from text that may wrap it in prose or a code fence.
     *
     * <p>A whole-document parse is tried first, then every {@code '&#123;'} is offered to the parser
     * until one yields an object. Models routinely preface JSON with a sentence and a fence.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObjectLoose(String text) {
        try {
            Object value = parse(text);
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
        } catch (JsonException ignored) {
            // Fall through to the scan below.
        }
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != '{') {
                continue;
            }
            try {
                Object value = JsonParser.parseValueAt(text, index);
                if (value instanceof Map) {
                    return (Map<String, Object>) value;
                }
            } catch (JsonException ignored) {
                // Try the next candidate start.
            }
        }
        throw new JsonException("model did not return a JSON object");
    }

    /**
     * Renders with the readable separators {@code ", "} and {@code ": "} on one line.
     */
    public static String write(Object value) {
        return render(normalize(value), -1, false, Separators.DEFAULT);
    }

    /** Renders one member per line, indented by {@code indent} spaces per level. */
    public static String write(Object value, int indent) {
        return render(normalize(value), indent, false, Separators.INDENTED);
    }

    /**
     * Renders with sorted object keys and no optional whitespace, so two structurally identical
     * documents produce identical text. Used wherever the framework derives a digest from content.
     */
    public static String canonical(Object value) {
        return render(normalize(value), -1, true, Separators.COMPACT);
    }

    /** Converts models, paths, and exotic numbers into plain JSON data. */
    public static Object normalize(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Double || value instanceof Float) {
            return value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof Jsonable) {
            return normalize(((Jsonable) value).toJson());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            }
            return result;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>(collection.size());
            for (Object item : collection) {
                result.add(normalize(item));
            }
            return result;
        }
        if (value instanceof Object[] array) {
            List<Object> result = new ArrayList<>(array.length);
            for (Object item : array) {
                result.add(normalize(item));
            }
            return result;
        }
        if (value instanceof Path path) {
            return path.toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof BigInteger bigInteger) {
            return bigInteger.longValue();
        }
        return String.valueOf(value);
    }

    private static String render(Object normalized, int indent, boolean sortKeys, Separators separators) {
        StringBuilder builder = new StringBuilder();
        writeValue(normalized, builder, indent, 0, sortKeys, separators);
        return builder.toString();
    }

    /**
     * How a container separates its members.
     *
     * <p>The three renderings the framework needs differ in exactly these two strings: indented output
     * drops the space after the item separator but keeps it after the key separator.
     */
    private record Separators(String item, String key) {

        static final Separators DEFAULT = new Separators(", ", ": ");
        static final Separators INDENTED = new Separators(",", ": ");
        static final Separators COMPACT = new Separators(",", ":");
    }

    /** Renders an already-normalized value. */
    private static void writeValue(
            Object value, StringBuilder builder, int indent, int level, boolean sortKeys, Separators separators) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String text) {
            writeString(text, builder);
        } else if (value instanceof Boolean flag) {
            builder.append(flag ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            builder.append(formatDouble(((Number) value).doubleValue()));
        } else if (value instanceof Number number) {
            builder.append(number.longValue());
        } else if (value instanceof Map<?, ?> map) {
            writeObject(map, builder, indent, level, sortKeys, separators);
        } else if (value instanceof Collection<?> collection) {
            writeArray(collection, builder, indent, level, sortKeys, separators);
        } else if (value instanceof Object[] array) {
            writeArray(array, builder, indent, level, sortKeys, separators);
        } else {
            writeString(String.valueOf(value), builder);
        }
    }

    private static void writeObject(
            Map<?, ?> map,
            StringBuilder builder,
            int indent,
            int level,
            boolean sortKeys,
            Separators separators) {
        if (map.isEmpty()) {
            builder.append("{}");
            return;
        }
        Map<String, Object> entries = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            entries.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        if (sortKeys) {
            entries = new TreeMap<>(entries);
        }
        builder.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            if (!first) {
                builder.append(separators.item());
            }
            first = false;
            newline(builder, indent, level + 1);
            writeString(entry.getKey(), builder);
            builder.append(separators.key());
            writeValue(entry.getValue(), builder, indent, level + 1, sortKeys, separators);
        }
        newline(builder, indent, level);
        builder.append('}');
    }

    private static void writeArray(
            Collection<?> collection,
            StringBuilder builder,
            int indent,
            int level,
            boolean sortKeys,
            Separators separators) {
        if (collection.isEmpty()) {
            builder.append("[]");
            return;
        }
        builder.append('[');
        boolean first = true;
        for (Object item : collection) {
            if (!first) {
                builder.append(separators.item());
            }
            first = false;
            newline(builder, indent, level + 1);
            writeValue(item, builder, indent, level + 1, sortKeys, separators);
        }
        newline(builder, indent, level);
        builder.append(']');
    }

    private static void writeArray(
            Object[] array,
            StringBuilder builder,
            int indent,
            int level,
            boolean sortKeys,
            Separators separators) {
        List<Object> items = new ArrayList<>(array.length);
        for (Object item : array) {
            items.add(item);
        }
        writeArray(items, builder, indent, level, sortKeys, separators);
    }

    private static void newline(StringBuilder builder, int indent, int level) {
        if (indent < 0) {
            return;
        }
        builder.append('\n');
        builder.append(" ".repeat(indent * level));
    }

    private static void writeString(String text, StringBuilder builder) {
        builder.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        builder.append('"');
    }

    /**
     * Renders a double so a reader can still tell it from an integer: an integral value keeps a
     * {@code .0} suffix, and the non-finite values use the bare {@code NaN}/{@code Infinity} literals.
     */
    private static String formatDouble(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        return Double.toString(value);
    }
}
