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
