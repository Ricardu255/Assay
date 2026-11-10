package io.assay.server.run;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body for {@code POST /api/runs}. */
public record RunRequest(
        String adapter,
        @JsonProperty("adapter_class") String adapterClass,
        String cases,
        String suite,
        String source,
        @JsonProperty("run_id") String runId,
        Boolean resume) {
}
