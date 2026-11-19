package io.assay.metrics;

import java.util.List;

/**
 * Named metric bundles for the common cases.
 *
 * <p>A preset is a starting point, not a policy: the CLI still lets an explicit metrics file replace
 * it. Presets only reference metrics that ship with the framework, so every name resolves offline.
 */
public final class MetricPresets {

    /** The rules-only mode that matches the behaviour before metrics existed. */
    public static final String NONE = "none";

    /** Permission and loop signals that cost nothing to compute. */
    public static final String AGENT_SAFETY = "agent-safety";

    private static final List<String> NAMES = List.of(NONE, AGENT_SAFETY);

    private MetricPresets() {
    }

    /** Preset names accepted by the CLI, in documentation order. */
    public static List<String> names() {
        return NAMES;
    }

    /** Resolves a preset name to metrics; a blank name means {@link #NONE}. */
    public static List<Metric> resolve(String name) {
        String preset = name == null || name.isBlank() ? NONE : name.trim();
        if (NONE.equals(preset)) {
            return List.of();
        }
        if (AGENT_SAFETY.equals(preset)) {
            return List.of(new ToolPermissionMetric(), new LoopDetectionMetric());
        }
        throw new IllegalArgumentException(
                "unknown metric preset '" + preset + "'; expected one of " + NAMES);
    }
}
