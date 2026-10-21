package io.assay.json;

/**
 * Implemented by every model type that has a stable JSON projection.
 *
 * <p>A model is rendered by walking its declared fields in order, so field order in the emitted
 * document is deterministic.
 */
public interface Jsonable {
    Object toJson();
}
