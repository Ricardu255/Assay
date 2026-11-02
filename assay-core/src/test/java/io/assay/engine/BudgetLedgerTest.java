package io.assay.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.assay.model.EvaluationBudget;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The ledger must reserve before a call and never treat missing usage as zero. */
class BudgetLedgerTest {

    @Test
    void aDisabledBudgetIsANoOp() {
        BudgetLedger ledger = new BudgetLedger(EvaluationBudget.disabled());

        assertFalse(ledger.enabled());
        assertNotNull(ledger.reserve(1_000_000));
        ledger.settleFailure(ledger.reserve(10));
        Map<String, Object> snapshot = ledger.snapshot();

        assertFalse((Boolean) snapshot.get("enabled"));
        assertEquals(0L, snapshot.get("spent_tokens"));
        assertFalse((Boolean) snapshot.get("exhausted"));
    }

    @Test
    void reservesTheOutputAllowanceBeforeTheCall() {
        BudgetLedger ledger = new BudgetLedger(new EvaluationBudget(10, null, 2, 0));

        BudgetLedger.Reservation first = ledger.reserve(3);
        assertNotNull(first);
        assertEquals(5, first.tokens());

        BudgetLedger.Reservation second = ledger.reserve(6);
        assertNull(second);
        assertTrue(ledger.exhausted());
        assertEquals(5L, ledger.snapshot().get("reserved_tokens"));
    }

    @Test
    void settlesReportedTokensAndChargesTheReservationWhenTheyAreMissing() {
        BudgetLedger ledger = new BudgetLedger(new EvaluationBudget(10, null, 2, 0));
        BudgetLedger.Reservation first = ledger.reserve(3);
        ledger.settle(first, new BudgetUsage(4L, null));

        BudgetLedger.Reservation second = ledger.reserve(1);
        ledger.settle(second, BudgetUsage.unknown());

        Map<String, Object> snapshot = ledger.snapshot();
        assertEquals(7L, snapshot.get("spent_tokens"));
        assertEquals(2, snapshot.get("calls"));
        assertEquals(1, snapshot.get("unreported_token_calls"));
        assertEquals(0, snapshot.get("unreported_cost_calls"));
    }

    @Test
    void aFailureChargesItsReservationBecauseUsageIsUnknown() {
        BudgetLedger ledger = new BudgetLedger(new EvaluationBudget(10, null, 2, 0));
        BudgetLedger.Reservation reservation = ledger.reserve(3);

        ledger.settleFailure(reservation);

        Map<String, Object> snapshot = ledger.snapshot();
        assertEquals(5L, snapshot.get("spent_tokens"));
        assertEquals(1, snapshot.get("failed_calls"));
        assertEquals(0L, snapshot.get("reserved_tokens"));
    }

    @Test
    void actualUsageMayOverrunAndIsRecorded() {
        BudgetLedger ledger = new BudgetLedger(new EvaluationBudget(5, null, 0, 0));
        BudgetLedger.Reservation reservation = ledger.reserve(3);

        ledger.settle(reservation, new BudgetUsage(8L, null));

        Map<String, Object> snapshot = ledger.snapshot();
        assertEquals(8L, snapshot.get("spent_tokens"));
        assertEquals(-3L, snapshot.get("remaining_tokens"));
        assertTrue((Boolean) snapshot.get("overrun"));
    }

    @Test
    void costReservationsAreIndependentOfTokenLimits() {
        BudgetLedger ledger = new BudgetLedger(new EvaluationBudget(null, 0.05, 0, 0.02));

        assertNotNull(ledger.reserve(1));
        assertNotNull(ledger.reserve(1));
        assertNull(ledger.reserve(1));

        Map<String, Object> snapshot = ledger.snapshot();
        assertNull(snapshot.get("max_total_tokens"));
        assertEquals(0.05, (Double) snapshot.get("max_cost_usd"), 1e-9);
        assertTrue((Boolean) snapshot.get("exhausted"));
    }

    @Test
    void concurrentReservationsCannotOverspendTheLimit() throws Exception {
        BudgetLedger ledger = new BudgetLedger(new EvaluationBudget(5, null, 0, 0));
        java.util.concurrent.atomic.AtomicInteger granted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        for (int index = 0; index < 20; index++) {
            Thread thread = new Thread(() -> {
                if (ledger.reserve(1) != null) {
                    granted.incrementAndGet();
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(5, granted.get());
        assertEquals(5L, ledger.snapshot().get("reserved_tokens"));
    }

    @Test
    void resumeSeedsPreviousSpendIntoTheSameBudget() {
        BudgetLedger ledger = new BudgetLedger(new EvaluationBudget(10, null, 0, 0));
        ledger.seed(new BudgetUsage(7L, 0.01));

        assertNotNull(ledger.reserve(2));
        assertNull(ledger.reserve(2));

        Map<String, Object> snapshot = ledger.snapshot();
        assertEquals(7L, snapshot.get("spent_tokens"));
        assertEquals(1, snapshot.get("seeded_cases"));
    }
}
