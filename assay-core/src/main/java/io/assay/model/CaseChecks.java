package io.assay.model;

import java.util.Map;

/** Shared updates applied when a check is appended to a stored case record. */
public final class CaseChecks {

    private CaseChecks() {
    }

    /**
     * Applies the hard/soft/candidate rules to {@code record}.
     *
     * <p>A failed hard check clears {@code hard_pass}; a failed soft or candidate check increments
     * {@code soft_warning_count}. Other checks leave the record unchanged.
     *
     * @return the same record, for convenient chaining
     */
    public static Map<String, Object> apply(Map<String, Object> record, CheckResult check) {
        if ("hard".equals(check.level()) && !check.passed()) {
            record.put("hard_pass", false);
        } else if (("soft".equals(check.level()) || "candidate".equals(check.level())) && !check.passed()) {
            record.put("soft_warning_count", Values.toInt(record.get("soft_warning_count"), 0) + 1);
        }
        return record;
    }
}
