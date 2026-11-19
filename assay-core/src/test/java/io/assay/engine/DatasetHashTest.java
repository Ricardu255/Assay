package io.assay.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;

class DatasetHashTest {

    @Test
    void isIndependentOfCaseOrderAndChangesWithContent() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("C1", "h1");
        first.put("C2", "h2");
        Map<String, String> reordered = new LinkedHashMap<>();
        reordered.put("C2", "h2");
        reordered.put("C1", "h1");

        String hash = EvaluationEngine.datasetHash(first);

        assertEquals(64, hash.length());
        assertEquals(hash, EvaluationEngine.datasetHash(reordered));
        assertNotEquals(hash, EvaluationEngine.datasetHash(Map.of("C1", "h2", "C2", "h2")));
    }
}
