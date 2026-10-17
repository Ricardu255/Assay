const { test } = require("node:test");
const assert = require("node:assert/strict");
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
