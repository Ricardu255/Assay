package io.assay.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small coercions shared by the model layer and the storage backends. */
public final class Values {

    private Values() {
    }

    public static double toDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static int toInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** Coerces a value to int, treating unparseable values as zero. */
    public static int toInt(Object value) {
        return toInt(value, 0);
    }

    /** Reads a numeric value without parsing strings; non-numbers become zero. */
    public static int numberAsInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** Reads a numeric value without parsing strings; non-numbers become zero. */
    public static double numberAsDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    /** Returns the value as a mutable string-keyed map, or an empty map when it is not one. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    /** Returns the value as a mutable list, or an empty list when it is not one. */
    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        return value instanceof List<?> ? (List<Object>) value : new ArrayList<>();
    }

    static double doubleField(Map<String, Object> values, String field, double fallback) {
        if (!values.containsKey(field)) {
            return fallback;
        }
        Object value = values.get(field);
        double parsed;
        try {
            if (value instanceof Number number) {
                parsed = number.doubleValue();
            } else if (value instanceof String text) {
                parsed = Double.parseDouble(text.trim());
            } else {
                throw new IllegalArgumentException(field + " must be a finite number");
            }
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(field + " must be a finite number", error);
        }
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException(field + " must be a finite number");
        }
        return parsed;
    }

    static int intField(Map<String, Object> values, String field, int fallback) {
        if (!values.containsKey(field)) {
            return fallback;
        }
        Object value = values.get(field);
        if (!(value instanceof Number) && !(value instanceof String)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return new BigDecimal(value.toString().trim()).intValueExact();
        } catch (NumberFormatException | ArithmeticException error) {
            throw new IllegalArgumentException(field + " must be an integer", error);
        }
    }

    static boolean booleanField(Map<String, Object> values, String field, boolean fallback) {
        if (!values.containsKey(field)) {
            return fallback;
        }
        if (!(values.get(field) instanceof Boolean result)) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return result;
    }

    static void nonNegative(String field, double value) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(field + " must be finite and non-negative");
        }
    }

    static void rate(String field, double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(field + " must be finite and between 0 and 1");
        }
    }
}
