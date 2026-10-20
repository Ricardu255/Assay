package io.assay.model;

import io.assay.json.Jsonable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.assay.support.Immutable;

/** One verdict about how much test coverage a change needs. */
public record TestImpactAssessment(
        String mode,
        double confidence,
        String risk,
        List<String> reasons,
        String source,
        String inputKind)
        implements Jsonable {

    public TestImpactAssessment {
        if (!List.of("smoke", "regression", "full").contains(mode)) {
            throw new IllegalArgumentException("mode must be smoke, regression, or full");
        }
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (!List.of("low", "medium", "high").contains(risk)) {
            throw new IllegalArgumentException("risk must be low, medium, or high");
        }
        reasons = reasons == null ? List.of() : Immutable.list(reasons);
        source = source == null ? "rules" : source;
        inputKind = inputKind == null ? "metadata" : inputKind;
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("confidence", confidence);
        result.put("risk", risk);
        result.put("reasons", reasons);
        result.put("source", source);
        result.put("input_kind", inputKind);
        return result;
    }
}
