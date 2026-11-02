package io.assay.engine;

import io.assay.model.EvaluationBudget;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-safe ledger for one run's token and cost budget.
 *
 * <p>Each adapter call reserves an estimate before it starts. When the trace comes back, the ledger
 * releases the reservation and charges the reported usage; if the trace omits usage, the reservation
 * is charged instead and counted as unreported. The first denied reservation marks the run exhausted,
 * and the caller stops submitting new cases.
 */
public final class BudgetLedger {

    /** One call's estimated charge, held until the call settles. */
    public record Reservation(long tokens, double costUsd) {
    }

    private final EvaluationBudget budget;
    private long reservedTokens;
    private double reservedCostUsd;
    private long spentTokens;
    private double spentCostUsd;
    private int calls;
    private int failedCalls;
    private int unreportedTokenCalls;
    private int unreportedCostCalls;
    private int seededCases;
    private boolean exhausted;
    private boolean overrun;

    public BudgetLedger(EvaluationBudget budget) {
        this.budget = budget == null ? EvaluationBudget.disabled() : budget;
    }

    public boolean enabled() {
        return budget.enabled();
    }

    public EvaluationBudget budget() {
        return budget;
    }

    /**
     * Reserves one call, or returns {@code null} when the estimate no longer fits.
     *
     * @param inputTokens estimated prompt tokens for this call; negative values are treated as zero
     */
    public synchronized Reservation reserve(long inputTokens) {
        if (!enabled()) {
            return new Reservation(0, 0);
        }
        long tokens = budget.maxTotalTokens() == null
                ? 0
                : saturatingAdd(Math.max(0, inputTokens), budget.reserveOutputTokens());
        double cost = budget.maxCostUsd() == null ? 0 : budget.reserveCostUsd();
        long committedTokens = saturatingAdd(reservedTokens, spentTokens);
        if (exceedsTokens(committedTokens, tokens)
                || exceedsCost(reservedCostUsd + spentCostUsd, cost)) {
            exhausted = true;
            return null;
        }
        reservedTokens = saturatingAdd(reservedTokens, tokens);
        reservedCostUsd += cost;
        return new Reservation(tokens, cost);
    }

    /** Releases a reservation and charges the trace's reported usage, or the reservation if absent. */
    public synchronized void settle(Reservation reservation, BudgetUsage usage) {
        if (!enabled() || reservation == null) {
            return;
        }
        releaseReserved(reservation);
        BudgetUsage actual = usage == null ? BudgetUsage.unknown() : usage;
        calls++;
        if (actual.tokensReported()) {
            spentTokens = saturatingAdd(spentTokens, actual.totalTokens());
        } else if (budget.maxTotalTokens() != null) {
            spentTokens = saturatingAdd(spentTokens, reservation.tokens());
            unreportedTokenCalls++;
        }
        if (actual.costReported()) {
            spentCostUsd += actual.costUsd();
        } else if (budget.maxCostUsd() != null) {
            spentCostUsd += reservation.costUsd();
            unreportedCostCalls++;
        }
        updateOverrun();
    }

    /** Charges a call that failed before producing a readable trace; its real usage is unknown. */
    public synchronized void settleFailure(Reservation reservation) {
        if (!enabled() || reservation == null) {
            return;
        }
        releaseReserved(reservation);
        calls++;
        failedCalls++;
        spentTokens = saturatingAdd(spentTokens, reservation.tokens());
        spentCostUsd += reservation.costUsd();
        updateOverrun();
    }

    /**
     * Counts usage already stored by a previous attempt.
     *
     * <p>Resume keeps the same run id, so the budget must see the earlier spend; otherwise a resumed
     * run would get a fresh allowance and could spend twice the configured cap.
     */
    public synchronized void seed(BudgetUsage usage) {
        if (!enabled()) {
            return;
        }
        seededCases++;
        BudgetUsage actual = usage == null ? BudgetUsage.unknown() : usage;
        if (actual.tokensReported()) {
            spentTokens = saturatingAdd(spentTokens, actual.totalTokens());
        } else if (budget.maxTotalTokens() != null) {
            unreportedTokenCalls++;
        }
        if (actual.costReported()) {
            spentCostUsd += actual.costUsd();
        } else if (budget.maxCostUsd() != null) {
            unreportedCostCalls++;
        }
        updateOverrun();
    }

    public synchronized boolean exhausted() {
        return exhausted;
    }

    /** Releases a reservation without charging it, for a call that never reached the provider. */
    public synchronized void release(Reservation reservation) {
        if (!enabled() || reservation == null) {
            return;
        }
        releaseReserved(reservation);
    }

    public synchronized boolean overrun() {
        return overrun;
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled());
        result.put("max_total_tokens", budget.maxTotalTokens());
        result.put("max_cost_usd", budget.maxCostUsd());
        result.put("reserve_output_tokens", budget.reserveOutputTokens());
        result.put("reserve_cost_usd", money(budget.reserveCostUsd()));
        result.put("reserved_tokens", reservedTokens);
        result.put("reserved_cost_usd", money(reservedCostUsd));
        result.put("spent_tokens", spentTokens);
        result.put("spent_cost_usd", money(spentCostUsd));
        result.put(
                "remaining_tokens",
                budget.maxTotalTokens() == null ? null : (long) budget.maxTotalTokens() - spentTokens);
        result.put(
                "remaining_cost_usd",
                budget.maxCostUsd() == null ? null : money(budget.maxCostUsd() - spentCostUsd));
        result.put("calls", calls);
        result.put("failed_calls", failedCalls);
        result.put("unreported_token_calls", unreportedTokenCalls);
        result.put("unreported_cost_calls", unreportedCostCalls);
        result.put("seeded_cases", seededCases);
        result.put("exhausted", exhausted);
        result.put("overrun", overrun);
        return result;
    }

    private void releaseReserved(Reservation reservation) {
        reservedTokens = Math.max(0, reservedTokens - reservation.tokens());
        reservedCostUsd = Math.max(0, reservedCostUsd - reservation.costUsd());
    }

    private boolean exceedsTokens(long committed, long additional) {
        return budget.maxTotalTokens() != null
                && saturatingAdd(committed, additional) > budget.maxTotalTokens();
    }

    private boolean exceedsCost(double committed, double additional) {
        return budget.maxCostUsd() != null && committed + additional > budget.maxCostUsd() + 1e-12;
    }

    private void updateOverrun() {
        if (budget.maxTotalTokens() != null && spentTokens > budget.maxTotalTokens()) {
            overrun = true;
        }
        if (budget.maxCostUsd() != null && spentCostUsd > budget.maxCostUsd() + 1e-12) {
            overrun = true;
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static double money(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
