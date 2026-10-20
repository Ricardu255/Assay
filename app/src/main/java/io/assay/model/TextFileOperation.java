package io.assay.model;

import io.assay.json.Jsonable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A bounded, text-only file mutation proposed by a candidate. */
public record TextFileOperation(String operation, String path, String content, String destination)
        implements Jsonable {

    public TextFileOperation {
        if (path == null) {
            throw new IllegalArgumentException("path must be a string");
        }
        if (!List.of("write", "delete", "move").contains(operation)) {
            throw new IllegalArgumentException("unsupported text file operation: " + operation);
        }
        if ("write".equals(operation) && content == null) {
            throw new IllegalArgumentException("write operation requires string content");
        }
        if ("move".equals(operation) && (destination == null || destination.isEmpty())) {
            throw new IllegalArgumentException("move operation requires destination");
        }
        if (!"move".equals(operation) && destination != null) {
            throw new IllegalArgumentException(operation + " operation must not include destination");
        }
        if (!"write".equals(operation) && content != null) {
            throw new IllegalArgumentException(operation + " operation must not include content");
        }
        SafePath.require("path", path);
        SafePath.require("destination", destination);
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", operation);
        result.put("path", path);
        result.put("content", content);
        result.put("destination", destination);
        return result;
    }

    public static TextFileOperation fromJson(Map<String, Object> value) {
        return new TextFileOperation(
                String.valueOf(value.getOrDefault("operation", "")),
                String.valueOf(value.getOrDefault("path", "")),
                value.get("content") == null ? null : String.valueOf(value.get("content")),
                value.get("destination") == null ? null : String.valueOf(value.get("destination")));
    }

    public static List<TextFileOperation> listFromJson(Object value) {
        List<TextFileOperation> result = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                result.add(fromJson(EvalCase.asMap(item)));
            }
        }
        return result;
    }
}
