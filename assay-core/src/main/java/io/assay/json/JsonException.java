package io.assay.json;

/** Raised when text cannot be read as JSON. */
public class JsonException extends RuntimeException {

    public JsonException(String message) {
        super(message);
    }
}
