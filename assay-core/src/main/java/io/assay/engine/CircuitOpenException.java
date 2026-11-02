package io.assay.engine;

/** Raised before an external call when the circuit breaker is open. */
public final class CircuitOpenException extends RuntimeException {

    public CircuitOpenException(String message) {
        super(message);
    }
}
