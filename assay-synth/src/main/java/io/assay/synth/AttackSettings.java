package io.assay.synth;

import java.util.List;

/** Configuration for one adversarial generation run. */
public record AttackSettings(
        List<AttackCategory> categories,
        String scenario,
        int variantsPerCase,
        SynthesisSettings.Limits limits,
        double duplicateThreshold) {

    public AttackSettings {
        if (categories == null || categories.isEmpty()) {
            throw new IllegalArgumentException("attack categories must not be empty");
        }
        if (scenario == null || scenario.isBlank()) {
            throw new IllegalArgumentException("attack scenario must be non-empty");
        }
        if (variantsPerCase < 1 || variantsPerCase > 5) {
            throw new IllegalArgumentException("variants_per_case must be between 1 and 5");
        }
        if (!Double.isFinite(duplicateThreshold) || duplicateThreshold < 0 || duplicateThreshold > 1) {
            throw new IllegalArgumentException("duplicate_threshold must be between 0 and 1");
        }
        categories = List.copyOf(categories);
        scenario = scenario.trim();
        limits = limits == null ? new SynthesisSettings.Limits(100, 300, 600_000) : limits;
    }

    /** Defaults for the CLI. */
    public static AttackSettings defaults() {
        return new AttackSettings(
                List.of(AttackCategory.values()),
                "adversarial",
                1,
                new SynthesisSettings.Limits(100, 300, 600_000),
                0.9);
    }
}
