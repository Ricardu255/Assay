package io.assay.cli;

import io.assay.json.Json;
import io.assay.runner.Job;
import io.assay.runner.JobStore;
import java.util.LinkedHashMap;
import java.util.Map;

/** Queues one evaluation run from the CLI. */
final class QueueSubmitCommand {

    private QueueSubmitCommand() {
    }

    static int submit(CommandLine.Namespace args) {
        try (JobStore store = QueueSupport.jobStore(args)) {
            String idempotencyKey = args.string("idempotency-key");
            if (idempotencyKey != null) {
                idempotencyKey = idempotencyKey.trim();
                if (idempotencyKey.isEmpty()) {
                    idempotencyKey = null;
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("adapter", args.string("adapter"));
            if (args.string("adapter-class") != null) {
                payload.put("adapter_class", args.string("adapter-class"));
            }
            payload.put("suite", args.string("suite"));
            payload.put("cases", args.string("cases"));
            payload.put("store", args.string("store"));
            payload.put("output", args.string("output"));
            payload.put("source", args.string("source"));
            payload.put("workers", args.integer("workers"));
            payload.put("retries", args.integer("retries"));
            payload.put("timeout", args.decimal("timeout"));
            if (args.integerOrNull("max-total-tokens") != null) {
                payload.put("max_total_tokens", args.integerOrNull("max-total-tokens"));
            }
            if (args.decimalOrNull("max-cost-usd") != null) {
                payload.put("max_cost_usd", args.decimalOrNull("max-cost-usd"));
            }
            payload.put("reserve_output_tokens", args.integer("reserve-output-tokens"));
            payload.put("reserve_cost_usd", args.decimal("reserve-cost-usd"));
            payload.put("retry_base_seconds", args.decimal("retry-base-seconds"));
            payload.put("retry_max_seconds", args.decimal("retry-max-seconds"));
            payload.put("retry_jitter", args.decimal("retry-jitter"));
            payload.put("breaker_failures", args.integer("breaker-failures"));
            payload.put("breaker_reset_seconds", args.decimal("breaker-reset-seconds"));
            payload.put("use_llm", args.flag("use-llm"));
            payload.put("collect_few_shot", args.flag("collect-few-shot"));
            if (args.string("run-identity") != null) {
                payload.put("run_identity", args.string("run-identity"));
            }
            if (args.string("preset") != null) {
                payload.put("metric_preset", args.string("preset"));
            }
            if (args.path("metrics") != null) {
                payload.put("metrics_file", args.path("metrics").toAbsolutePath().normalize().toString());
            }
            if (args.string("metric-cache") != null) {
                payload.put("metric_cache", args.string("metric-cache"));
            }
            if (args.string("base-url") != null) {
                payload.put("llm_base_url", args.string("base-url"));
            }
            if (args.string("model") != null) {
                payload.put("llm_model", args.string("model"));
            }
            String runId = args.string("run-id");
            if (runId == null || runId.isBlank()) {
                // A retried idempotent submit must produce the same payload; a random run id would
                // make the same request look like a conflict.
                runId = idempotencyKey == null
                        ? "queued-" + CliIds.shortId()
                        : "queued-" + CliIds.stableId(idempotencyKey);
            }
            payload.put("run_id", runId);
            Job job = store.submit(idempotencyKey, payload, args.integer("max-attempts"));
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("job_id", job.id());
            output.put("run_id", job.payload().getOrDefault("run_id", runId));
            output.put("status", job.status().name());
            if (job.idempotencyKey() != null) {
                output.put("idempotency_key", job.idempotencyKey());
            }
            System.out.println(Json.write(output));
            return 0;
        }
    }
}
