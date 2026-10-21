package io.assay.engine;

import io.assay.model.AutoEvolutionAdapter;

/** Supplies a text-evolution adapter — the counterpart of {@link ProjectAdapterFactory} for evolve-auto runs. */
public interface AutoEvolutionAdapterFactory {

    AutoEvolutionAdapter create();
}
