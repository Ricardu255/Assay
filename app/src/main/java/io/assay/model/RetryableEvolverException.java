package io.assay.model;

/** The evolver response was unusable, but repeating the call may recover. */
public class RetryableEvolverException extends IllegalArgumentException {

    public RetryableEvolverException(String message) {
        super(message);
    }

    public RetryableEvolverException(String message, Throwable cause) {
        super(message, cause);
    }
}
