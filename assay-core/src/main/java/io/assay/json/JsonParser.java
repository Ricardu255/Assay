package io.assay.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive-descent JSON reader.
 *
 * <p>Values map onto plain Java types: objects are {@link LinkedHashMap}, arrays are {@link ArrayList},
 * integral numbers are {@link Long}, fractional numbers are {@link Double}, and the remaining scalars
 * are {@link String}, {@link Boolean} and {@code null}.
 */
final class JsonParser {

    private final String text;
    private int position;

    private JsonParser(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        JsonParser parser = new JsonParser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.position != text.length()) {
            throw new JsonException("trailing content at offset " + parser.position);
        }
        return value;
    }

    /**
     * Reads one JSON value starting at {@code start}, ignoring anything that follows it.
     *
     * <p>This is what lets a caller lift an object out of surrounding prose.
     */
    static Object parseValueAt(String text, int start) {
        JsonParser parser = new JsonParser(text);
        parser.position = start;
        parser.skipWhitespace();
        return parser.readValue();
    }

    private Object readValue() {
        if (position >= text.length()) {
            throw new JsonException("unexpected end of input");
        }
        char character = text.charAt(position);
        switch (character) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expectLiteral("true");
                return Boolean.TRUE;
            case 'f':
                expectLiteral("false");
                return Boolean.FALSE;
            case 'n':
                expectLiteral("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        position++;
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return result;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new JsonException("object key must be a string at offset " + position);
            }
            String key = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw new JsonException("expected ':' at offset " + position);
            }
            position++;
            skipWhitespace();
            result.put(key, readValue());
            skipWhitespace();
            char separator = peek();
            if (separator == ',') {
                position++;
                continue;
            }
            if (separator == '}') {
                position++;
                return result;
            }
            throw new JsonException("expected ',' or '}' at offset " + position);
        }
    }

    private List<Object> readArray() {
        List<Object> result = new ArrayList<>();
        position++;
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return result;
        }
        while (true) {
            skipWhitespace();
            result.add(readValue());
            skipWhitespace();
            char separator = peek();
            if (separator == ',') {
                position++;
                continue;
            }
            if (separator == ']') {
                position++;
                return result;
            }
            throw new JsonException("expected ',' or ']' at offset " + position);
        }
    }

    private String readString() {
        StringBuilder builder = new StringBuilder();
        position++;
        while (true) {
            if (position >= text.length()) {
                throw new JsonException("unterminated string");
            }
            char character = text.charAt(position++);
            if (character == '"') {
                return builder.toString();
            }
            if (character != '\\') {
                if (character < 0x20) {
                    throw new JsonException("control character in string at offset " + (position - 1));
                }
                builder.append(character);
                continue;
            }
            if (position >= text.length()) {
                throw new JsonException("unterminated escape sequence");
            }
            char escape = text.charAt(position++);
            switch (escape) {
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                case '/' -> builder.append('/');
                case 'b' -> builder.append('\b');
                case 'f' -> builder.append('\f');
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case 'u' -> builder.append(readUnicodeEscape());
                default -> throw new JsonException("invalid escape '\\" + escape + "'");
            }
        }
    }

    private char readUnicodeEscape() {
        if (position + 4 > text.length()) {
            throw new JsonException("truncated unicode escape");
        }
        String hex = text.substring(position, position + 4);
        position += 4;
        try {
            return (char) Integer.parseInt(hex, 16);
        } catch (NumberFormatException error) {
            throw new JsonException("invalid unicode escape '\\u" + hex + "'");
        }
    }

    private Object readNumber() {
        int start = position;
        if (peek() == '-') {
            position++;
        }
        boolean fractional = false;
        while (position < text.length()) {
            char character = text.charAt(position);
            if (character >= '0' && character <= '9') {
                position++;
            } else if (character == '.' || character == 'e' || character == 'E') {
                fractional = true;
                position++;
            } else if ((character == '+' || character == '-')
                    && (text.charAt(position - 1) == 'e' || text.charAt(position - 1) == 'E')) {
                position++;
            } else {
                break;
            }
        }
        String literal = text.substring(start, position);
        if (literal.isEmpty() || literal.equals("-")) {
            throw new JsonException("invalid number at offset " + start);
        }
        if (!fractional) {
            try {
                return Long.parseLong(literal);
            } catch (NumberFormatException ignored) {
                // Integers beyond Long range degrade to double; the value stays numeric either way.
                try {
                    return new BigDecimal(literal).doubleValue();
                } catch (NumberFormatException error) {
                    throw new JsonException("invalid number '" + literal + "'");
                }
            }
        }
        try {
            return Double.parseDouble(literal);
        } catch (NumberFormatException error) {
            throw new JsonException("invalid number '" + literal + "'");
        }
    }

    private void expectLiteral(String literal) {
        if (!text.startsWith(literal, position)) {
            throw new JsonException("invalid literal at offset " + position);
        }
        position += literal.length();
    }

    private char peek() {
        if (position >= text.length()) {
            throw new JsonException("unexpected end of input");
        }
        return text.charAt(position);
    }

    private void skipWhitespace() {
        while (position < text.length()) {
            char character = text.charAt(position);
            if (character == ' ' || character == '\t' || character == '\n' || character == '\r') {
                position++;
            } else {
                return;
            }
        }
    }
}
