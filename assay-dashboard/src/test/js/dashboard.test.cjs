const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const ui = require("../../main/resources/io/assay/web/dashboard-core.js");

test("JSONL checks aliases, duplicate IDs, line numbers and optional expectations", () => {
  assert.equal(ui.validateCases(ui.exampleCases).count, 1);
  const checked = ui.validateCases('\n{"case_id":"C1","payload":{}}\n{"id":"C1"}\n[]\n{"id":"C2","expected":[]}');
  assert.equal(checked.count, 1);
  assert.equal(checked.errors.length, 3);
  assert.match(checked.errors[0], /第 3 行.*重复/);
  assert.match(checked.errors[1], /第 4 行/);
  assert.ok(checked.warnings.some((warning) => warning.includes("expected")));
  assert.equal(ui.validateCases("  \n").errors.length, 1);
});

test("PowerShell and Bash commands quote shell metacharacters as literal arguments", () => {
  const config = { jar: "target/assay.jar", adapter: "folder's/$agent.jar", adapterClass: "org.demo.Agent$Factory",
    cases: "my cases;$(danger).jsonl", suite: "smoke", output: "results", shell: "powershell" };
  const command = ui.setupCommands(config);
  assert.deepEqual(command.errors, []);
  assert.ok(command.run.includes("'folder''s/$agent.jar'"));
  assert.ok(command.run.includes("'my cases;$(danger).jsonl'"));
  assert.ok(ui.setupCommands({ ...config, shell: "bash" }).run.includes("'folder'\"'\"'s/$agent.jar'"));
  assert.equal(ui.setupCommands({ ...config, cases: "bad\npath" }).run, "");
  assert.equal(ui.setupCommands({ ...config, adapter: "http://agent", adapterClass: "" }).run, "");
  assert.equal(ui.setupCommands({ ...config, cases: "" }).run, "");
});

test("Trace failures keep explicit errors and normalize provider statuses", () => {
  assert.equal(ui.failedEvent({ status: "FAILED" }), true);
  assert.equal(ui.failedEvent({ status: "ok", error: "tool timeout" }), true);
  assert.equal(ui.failedEvent({ status: "error" }), true);
  assert.equal(ui.failedEvent({ status: "pending" }), false);
  assert.equal(ui.failedEvent({ status: "ok", fields: { error_rate: 0.1 } }), false);
});

test("HTTP setup generates declarative config without accepting a literal API key", () => {
  const config = { mode: "http", jar: "target/assay.jar", adapter: "my-agent.http.json", cases: "http-cases.jsonl", suite: "smoke",
    output: ".assay/runs", shell: "powershell", endpoint: "http://localhost:9000/agent", outputPath: "data.answer",
    expectedPath: "keyword", operator: "contains", tokenEnv: "AGENT_API_KEY" };
  assert.deepEqual(ui.httpConfig(config), { errors: [], config: { type: "http", name: "my-agent", endpoint: config.endpoint,
    output_path: "data.answer", hard_gates: [{ name: "answer", actual: "final_output", operator: "contains", expected: "keyword" }],
    bearer_token_env: "AGENT_API_KEY" } });
  assert.equal(ui.setupCommands(config).errors.length, 0);
  assert.ok(ui.setupCommands(config).run.includes("'my-agent.http.json'"));
  assert.ok(ui.setupCommands(config).serve.includes("'--open-browser'"));
  assert.equal(ui.httpConfig({ ...config, tokenEnv: "" }).config.bearer_token_env, undefined);
  assert.ok(ui.httpConfig({ ...config, tokenEnv: "sk-secret-value" }).errors.length);
  for (const endpoint of ["file:///secret", "http://name:password@localhost/", "http://localhost/#fragment", "http://localhost/\nagent"]) {
    assert.equal(ui.setupCommands({ ...config, endpoint }).run, "");
  }
  for (const outputPath of ["data..answer", "data.9999999999", "", "data.\nanswer"]) {
    assert.equal(ui.httpConfig({ ...config, outputPath }).config, null);
  }
  assert.ok(ui.httpConfig({ ...config, outputPath: "choices.0.message.content" }).config);
  assert.equal(ui.validateCases(ui.httpCases).count, 1);
  assert.equal(ui.setupCommands({ ...config, adapter: "adapter.jar" }).run, "");
});

test("Diff parser preserves files, hunk line numbers, header-like code and missing newlines", () => {
  const patch = 'diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -7,2 +7,2 @@\n--- header-like code\n+new\n same\n\\ No newline at end of file\ndiff --git a/b.txt b/b.txt\nnew file mode 100644\n--- /dev/null\n+++ b/b.txt\n@@ -0,0 +1 @@\n+<script>literal</script>\n';
  const parsed = ui.parseDiff(patch);
  assert.equal(parsed.files.length, 2);
  assert.deepEqual(parsed.files[0].rows.filter((row) => row.oldLine || row.newLine), [
    { type: "deletion", text: "-- header-like code", oldLine: 7 },
    { type: "addition", text: "new", newLine: 7 },
    { type: "context", text: "same", oldLine: 8, newLine: 8 }
  ]);
  assert.equal(parsed.files[1].oldPath, null);
  assert.equal(parsed.files[1].rows.at(-1).newLine, 1);
  assert.equal(ui.parseDiff(patch, 2).truncated, true);
});

test("Diff parser handles renamed, binary, spaced and Git octal UTF-8 paths", () => {
  const parsed = ui.parseDiff('diff --git a/old name b/new name\nsimilarity index 100%\nrename from old name\nrename to new name\ndiff --git "a/\\344\\270\\255.txt" "b/\\344\\270\\255.txt"\nBinary files differ\n');
  assert.equal(parsed.files[0].oldPath, "old name");
  assert.equal(parsed.files[0].newPath, "new name");
  assert.equal(parsed.files[1].newPath, "中.txt");
  assert.equal(parsed.files[1].binary, true);
});

test("Split widths preserve both panel minimums and ignore corrupt preferences", () => {
  assert.equal(ui.splitRatio(0, 1000), 0.26);
  assert.ok(Math.abs((1 - ui.splitRatio(1, 1000)) * 1000 - 14 - 300) < 1e-10);
  assert.equal(ui.splitRatio(0.9, 400), 0.5);
  assert.equal(ui.splitRatio("broken", 1000), 0.5);
  assert.equal(ui.splitRatio(0.4, 1000), 0.4);
});

test("Case transitions expose regressions and cluster their first errors", () => {
  const first = { category: "step_fields", step: 2, candidate: { module: "Tool", action: "query_order" } };
  const payload = { comparisons: {
    improvement: {},
    regression: { case_transitions: { case_count: 2, regressed_count: 1, recovered_count: 0,
      persistent_failure_count: 1, stable_pass_count: 0,
      regressed: [{ case_id: "C1", critical: true, first_divergence: first }] } },
    holdout: { case_transitions: { case_count: 1, regressed_count: 1,
      regressed: [{ case_id: "C2", first_divergence: first }] } }
  } };
  const transitions = ui.caseTransitions(payload);
  assert.deepEqual(transitions.map(({ role }) => role), ["improvement", "regression", "holdout"]);
  assert.equal(transitions[0].data.case_count, undefined);
  const regressions = ui.regressions(payload);
  assert.deepEqual(regressions.map(({ role, case_id }) => `${role}:${case_id}`), ["regression:C1", "holdout:C2"]);
  const clusters = ui.failureClusters(regressions);
  assert.equal(clusters.length, 1);
  assert.equal(clusters[0].count, 2);
  assert.deepEqual(clusters[0].cases, ["C1", "C2"]);
  assert.equal(clusters[0].category, "step_fields");
  assert.deepEqual(ui.regressions({}), []);
  assert.deepEqual(ui.failureClusters(null), []);
});

test("Diagnosis text is localized from structured evidence", () => {
  const fields = { category: "step_fields", step: 2, changed_paths: ["route"],
    baseline_values: ["SUPPORT"], candidate_values: ["GENERAL"],
    baseline: { module: "IntentRouter", action: "route" }, candidate: { module: "IntentRouter", action: "route" } };
  assert.match(ui.divergenceText(fields), /第 2 步.*IntentRouter\/route.*route.*SUPPORT.*GENERAL/);
  assert.match(ui.divergenceText({ category: "step_changed", step: 2,
    baseline: { module: "Tool", action: "query_order" }, candidate: { module: "Tool", action: "estimate_delivery" } }),
  /Tool\/query_order.*Tool\/estimate_delivery/);
  assert.equal(ui.divergenceText({ category: "missing_trace" }), "缺少可读取的 Trace");
  assert.match(ui.divergenceText({ category: "final_output", baseline_values: ["old"], candidate_values: ["new"] }), /old.*new/);
  assert.match(ui.reasonText("holdout: candidate has hard failures"), /留出集.*候选存在硬失败/);
  assert.match(ui.reasonText("holdout/latency: required metric is missing"), /留出集\/latency.*必需指标缺失/);
  assert.match(ui.reasonText("regression: 2 regressed cases exceed the maximum 0"), /回归集.*2.*0/);
  assert.equal(ui.reasonText("something new"), "something new");
});

test("Run trends aggregate by local day and ignore runs outside the window", () => {
  const now = new Date(2026, 9, 21, 12);
  const stamp = (day, hour = 12) => new Date(2026, 9, day, hour).toISOString();
  const items = [
    { kind: "run", modified_at: stamp(21, 10), case_count: 10, hard_failures: 2, soft_warnings: 1, status: "passed", suite: "A" },
    { kind: "run", modified_at: stamp(21, 9), case_count: 5, hard_failures: 0, status: "passed", suite: "B" },
    { kind: "run", modified_at: stamp(20, 10), case_count: 4, hard_failures: 4, status: "failed", suite: "A" },
    { kind: "run", modified_at: stamp(1, 10), case_count: 99, hard_failures: 0, status: "passed", suite: "old" },
    { kind: "evolution", modified_at: stamp(21, 10), case_count: 7, hard_failures: 7 }
  ];
  const trend = ui.runTrend(items, 7, now);
  assert.equal(trend.days, 7);
  assert.equal(trend.buckets.length, 7);
  assert.deepEqual(trend.buckets.map((bucket) => bucket.label), ["10-15", "10-16", "10-17", "10-18", "10-19", "10-20", "10-21"]);
  const today = trend.buckets.at(-1), yesterday = trend.buckets.at(-2);
  assert.equal(today.runs, 2);
  assert.equal(today.caseCount, 15);
  assert.equal(today.hardFailures, 2);
  assert.equal(today.softWarnings, 1);
  assert.equal(today.passRate, 13 / 15);
  assert.equal(yesterday.passRate, 0);
  assert.equal(yesterday.failedRuns, 1);
  assert.equal(trend.totals.runs, 3);
  assert.equal(trend.totals.caseCount, 19);
  assert.equal(trend.totals.hardFailures, 6);
  assert.ok(!trend.buckets.some((bucket) => bucket.label === "10-01"));
});

test("Suite health picks the latest run and compares its pass rate", () => {
  const stamp = (day, hour = 12) => new Date(2026, 9, day, hour).toISOString();
  const health = ui.suiteHealth([
    { kind: "run", modified_at: stamp(21, 10), case_count: 10, hard_failures: 2, soft_warnings: 1, status: "passed", suite: "A" },
    { kind: "run", modified_at: stamp(20, 10), case_count: 4, hard_failures: 4, status: "failed", suite: "A" },
    { kind: "run", modified_at: stamp(21, 9), case_count: 5, hard_failures: 0, status: "passed", suite: "B" },
    { kind: "run", modified_at: "not-a-date", case_count: 2, hard_failures: 1, status: "failed" }
  ]);
  assert.equal(health.length, 3);
  assert.equal(health[0].suite, "A");
  assert.equal(health[0].runs, 2);
  assert.equal(health[0].caseCount, 14);
  assert.equal(health[0].passRate, 8 / 14);
  assert.equal(health[0].lastPassRate, 0.8);
  assert.equal(health[0].previousPassRate, 0);
  assert.equal(health[0].delta, 0.8);
  assert.equal(health[0].status, "warning");
  assert.equal(health[1].suite, "B");
  assert.equal(health[1].delta, null);
  assert.equal(ui.suiteHealth([], 8).length, 0);
  assert.equal(ui.suiteHealth([
    { kind: "run", modified_at: stamp(21, 10), case_count: 1, hard_failures: 0, status: "passed", suite: "A" }
  ], 1).length, 1);
});

test("Run trends return empty buckets instead of throwing on missing data", () => {
  const trend = ui.runTrend([], 7, new Date(2026, 9, 21, 12));
  assert.equal(trend.buckets.length, 7);
  assert.equal(trend.totals.runs, 0);
  assert.equal(trend.totals.passRate, null);
  assert.equal(trend.buckets.every((bucket) => bucket.passRate === null), true);
  assert.deepEqual(ui.suiteHealth([], 8), []);
});

test("Rule stats sort the worst pass rate first and preserve missing data", () => {
  const rows = ui.ruleStats({ rule_stats: {
    route: { level: "hard", evaluated: 4, passed: 2, pass_rate: 0.5, missing: 1 },
    tone: { level: "soft", evaluated: 2, passed: 0, pass_rate: 0, missing: 3 },
    never: { level: "hard", evaluated: 0, passed: 0, pass_rate: null, missing: 4 }
  } });
  assert.deepEqual(rows.map((row) => row.name), ["tone", "route", "never"]);
  assert.equal(rows[0].level, "soft");
  assert.equal(rows[0].passRate, 0);
  assert.equal(rows[2].passRate, null);
  assert.equal(rows[2].missing, 4);
  assert.deepEqual(ui.ruleStats({}), []);
});

test("Setup defaults point at the reactor artifact and source layout", () => {
  const source = fs.readFileSync(require.resolve("../../main/resources/io/assay/web/app.js"), "utf8");
  assert.match(source, /jar: "app\/target\/assay\.jar"/);
  assert.match(source, /app\/src\/main\/java\/io\/assay\/examples\/EchoAdapter\.java/);
});

test("Theme follows the system, persists explicit choices and survives unavailable storage", () => {
  const script = fs.readFileSync(require.resolve("../../main/resources/io/assay/web/theme.js"), "utf8");
  const document = { documentElement: { dataset: {} } }, window = {}, callbacks = [];
  const media = { matches: true, addEventListener: (_event, callback) => callbacks.push(callback) };
  let saved = '"invalid"';
  vm.runInNewContext(script, { document, window, matchMedia: () => media,
    localStorage: { getItem: () => saved, setItem: (_key, value) => { saved = value; } } });
  assert.equal(document.documentElement.dataset.theme, "dark");
  window.AssayTheme.set("light");
  assert.equal(saved, '"light"');
  media.matches = true; callbacks[0]();
  assert.equal(document.documentElement.dataset.theme, "light");
  window.AssayTheme.set("system");
  media.matches = false; callbacks[0]();
  assert.equal(document.documentElement.dataset.theme, "light");
  assert.doesNotThrow(() => vm.runInNewContext(script, { document, window, matchMedia: () => media,
    localStorage: { getItem() { throw new Error("blocked"); }, setItem() { throw new Error("blocked"); } } }));
  assert.doesNotThrow(() => window.AssayTheme.set("dark"));
});
