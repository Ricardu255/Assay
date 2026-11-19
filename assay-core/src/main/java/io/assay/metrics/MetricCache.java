package io.assay.metrics;

import io.assay.model.MetricResult;
import java.util.Optional;

/**
 * Stores metric results so an unchanged case and metric configuration can be replayed for free.
 *
 * <p>Only successful measurements ({@code PASS}/{@code FAIL}) are cached. A cache failure is an
 * optimization failure, never a run failure: callers must treat lookup and store errors as misses.
 */
public interface MetricCache {

    /** Returns a cached result for the key, or empty when there is none. */
    Optional<MetricResult> lookup(String key);

    /** Stores a result under the key. */
    void store(String key, MetricResult result);
}
