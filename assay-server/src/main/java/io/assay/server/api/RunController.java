package io.assay.server.api;

import io.assay.server.run.RunRequest;
import io.assay.server.run.RunService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The generic evaluation-run API. */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runs;

    public RunController(RunService runs) {
        this.runs = runs;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> start(
            @RequestBody RunRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.accepted().body(runs.start(request, idempotencyKey));
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return runs.list();
    }

    @GetMapping("/{runId}")
    public Map<String, Object> get(@PathVariable("runId") String runId) {
        return runs.get(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found: " + runId));
    }

    @GetMapping("/{runId}/cases")
    public List<Map<String, Object>> cases(@PathVariable("runId") String runId) {
        return runs.cases(runId);
    }
}
