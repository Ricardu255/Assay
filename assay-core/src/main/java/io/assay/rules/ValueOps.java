package io.assay.rules;

import java.util.List;
import java.util.Map;

/**
 * The value semantics the rule engine compares against.
 *
 * <p>Rules are declared as data, so how two values are compared is part of the framework's public
 * contract. Numbers compare across integer and floating representations, a boolean compares equal to
 * its numeric value, and a comparison between incompatible types is a failed check rather than an
 * error — a rule is a judgement about data, and data arriving in an unexpected shape is exactly what a
 * gate exists to catch.
 */
public final class ValueOps {

    /** Marks a path that resolved to nothing, so it stays distinguishable from a stored null. */
    public static final Object MISSING = new Object();

    private ValueOps() {
    }

    /** Structural equality over the JSON-shaped value set. */
    public static boolean equal(Object left, Object right) {
        if (left == MISSING || right == MISSING) {
            return left == right;
        }
        if (left == null || right == null) {
            return left == null && right == null;
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            if (left instanceof Boolean a && right instanceof Boolean b) {
                return a.equals(b);
            }
            // A boolean carries a numeric value, so true == 1 holds.
            Object number = left instanceof Boolean ? right : left;
            Object flag = left instanceof Boolean ? left : right;
            if (number instanceof Number value) {
                return value.doubleValue() == (Boolean.TRUE.equals(flag) ? 1d : 0d);
            }
            return false;
        }
        if (left instanceof Number a && right instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        if (left instanceof String a && right instanceof String b) {
            return a.equals(b);
        }
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
            if (!a.keySet().equals(b.keySet())) {
                return false;
            }
            for (Map.Entry<?, ?> entry : a.entrySet()) {
                if (!equal(entry.getValue(), b.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int index = 0; index < a.size(); index++) {
                if (!equal(a.get(index), b.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    /**
     * Ordering test on the JSON-shaped value set, or {@code null} when the two values are not
     * comparable. Ordinal comparison of two strings is allowed.
     */
    public static Boolean lessOrEqual(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return a.doubleValue() <= b.doubleValue();
        }
        if (left instanceof String a && right instanceof String b) {
            return a.compareTo(b) <= 0;
        }
        return null;
    }

    /**
     * Ordering test on the JSON-shaped value set, or {@code null} when the two values are not
     * comparable.
     */
    public static Boolean greaterOrEqual(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return a.doubleValue() >= b.doubleValue();
        }
        if (left instanceof String a && right instanceof String b) {
            return a.compareTo(b) >= 0;
        }
        return null;
    }

    /** Membership test for strings, lists, and mapping keys. */
    public static Boolean contains(Object haystack, Object needle) {
        if (haystack instanceof String text) {
            if (!(needle instanceof String part)) {
                return null;
            }
            return text.contains(part);
        }
        if (haystack instanceof Map<?, ?> map) {
            return map.containsKey(needle) || map.containsKey(String.valueOf(needle));
        }
        if (haystack instanceof List<?> list) {
            for (Object item : list) {
                if (equal(item, needle)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Reads a dotted path out of a JSON-shaped value.
     *
     * <p>Mapping keys are looked up directly, all-digit parts index into sequences, and anything else
     * yields {@link #MISSING}.
     */
    public static Object readPath(Object value, String path) {
        Object current = value;
        for (String part : path.split("\\.", -1)) {
            if (part.isEmpty()) {
                continue;
            }
            if (current instanceof Map<?, ?> map) {
                current = map.containsKey(part) ? map.get(part) : MISSING;
            } else if (current instanceof List<?> list && isDigits(part)) {
                int index = Integer.parseInt(part);
                current = index < list.size() ? list.get(index) : MISSING;
            } else {
                return MISSING;
            }
            if (current == MISSING) {
                return MISSING;
            }
        }
        return current;
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
