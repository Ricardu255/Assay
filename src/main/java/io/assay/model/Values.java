package io.assay.model;

import java.math.BigDecimal;
import java.util.Map;

/** Small numeric coercions shared by the model layer. */
final class Values {

    private Values() {
    }

    static double toDouble(Object value, double fallback) {
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

    static int toInt(Object value, int fallback) {
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
