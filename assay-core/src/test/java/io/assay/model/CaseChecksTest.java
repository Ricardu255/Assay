package io.assay.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;

class CaseChecksTest {

    @Test
    void failedHardCheckClearsHardPass() {
        Map<String, Object> record = new LinkedHashMap<>();

        CaseChecks.apply(record, new CheckResult("rule", "hard", "must-pass", false));

        assertEquals(false, record.get("hard_pass"));
        assertFalse(record.containsKey("soft_warning_count"));
    }

    @Test
    void failedSoftAndCandidateChecksIncrementWarnings() {
        Map<String, Object> record = new LinkedHashMap<>();

        CaseChecks.apply(record, new CheckResult("rule", "soft", "style", false));
        CaseChecks.apply(record, new CheckResult("rule", "candidate", "suggestion", false));

        assertEquals(2, record.get("soft_warning_count"));
    }

    @Test
    void passedAndUnknownLevelsLeaveRecordUntouched() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("hard_pass", true);
        record.put("soft_warning_count", 3);

        CaseChecks.apply(record, new CheckResult("rule", "hard", "must-pass", true));
        CaseChecks.apply(record, new CheckResult("rule", "info", "note", false));

        assertTrue(record.containsKey("hard_pass"));
        assertEquals(true, record.get("hard_pass"));
        assertEquals(3, record.get("soft_warning_count"));
    }
}
