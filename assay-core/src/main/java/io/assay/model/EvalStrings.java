package io.assay.model;

import java.util.ArrayList;
import java.util.List;

/** String list coercions shared by the model layer. */
final class EvalStrings {

    private EvalStrings() {
    }

    static List<String> list(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }
}
