package io.assay.engine;

/**
 * Raised before an adapter call that the run budget cannot fund.
 *
 * <p>The run loop treats this as "case not started", not as an evaluation failure, so a budget stop
 * never contaminates the pass rate with synthetic hard failures.
 */
public final class BudgetExhaustedException extends RuntimeException {

    public BudgetExhaustedException(String message) {
        super(message);
    }
}
