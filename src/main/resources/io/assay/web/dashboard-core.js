/* Shared, dependency-free validation and presentation helpers. */
(function (root) {
  "use strict";
  const exampleCases = '{"id":"EXAMPLE-001","scenario":"direct","input":{"message":"hello agent"},"expected":{"route":"ECHO","keyword":"hello"},"metadata":{"system_constraints":{"max_steps":3,"max_retries":0}}}';
  function validateCases(text) {
    const errors = [], warnings = [], ids = new Set(), scenarios = new Set();
    let count = 0;
    String(text).split(/\r?\n/).forEach((line, index) => {
      if (!line.trim()) return;
      const at = `第 ${index + 1} 行`;
      try {
        const row = JSON.parse(line);
        if (!row || typeof row !== "object" || Array.isArray(row)) throw new Error("应为 JSON 对象");
        const id = String(row.id ?? "").trim() || String(row.case_id ?? "").trim();
        if (!id) throw new Error("缺少 id 或 case_id");
        if (ids.has(id)) throw new Error(`重复用例 ID：${id}`);
        ids.add(id);
        for (const field of ["expected", "metadata"]) {
          if (row[field] != null && (typeof row[field] !== "object" || Array.isArray(row[field]))) {
            throw new Error(`${field} 应为对象`);
          }
        }
        if (!("input" in row) && !("payload" in row)) warnings.push(`${at}：没有 input 或 payload，将使用空输入。`);
        if (!row.expected || !Object.keys(row.expected).length) warnings.push(`${at}：未配置 expected，请确认规则是否使用固定期望值。`);
        scenarios.add(String(row.scenario ?? "default"));
        count++;
      } catch (error) { errors.push(`${at}：${error.message}`); }
    });
    if (!count && !errors.length) errors.push("测试集为空。每行需要一条 JSON 用例。");
    return { count, scenarios: [...scenarios], errors, warnings };
  }
  function shellQuote(value, shell = "powershell") {
    const text = String(value);
    if (/[\u0000-\u001f\u007f]/.test(text)) throw new Error("命令参数不能包含换行或控制字符");
    return shell === "bash" ? `'${text.replace(/'/g, `'"'"'`)}'` : `'${text.replace(/'/g, "''")}'`;
  }
  function setupCommands(config) {
    const errors = [];
    const name = /^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*$/;
    for (const [field, label] of [["jar", "框架 JAR"], ["adapter", "适配器"], ["cases", "测试集路径"], ["suite", "测试集名称"], ["output", "报告目录"]]) {
      if (!String(config[field] || "").trim()) errors.push(`请填写${label}。`);
    }
    if (config.adapter && !config.adapter.endsWith(".jar") && !name.test(config.adapter)) errors.push("适配器需要完整类名或以 .jar 结尾的路径。");
    if (config.adapterClass && (!config.adapter.endsWith(".jar") || !name.test(config.adapterClass))) errors.push("适配器类名仅用于 JAR，应填写完整类名。");
    let run = "", serve = "";
    try {
      const base = ["java", "-jar", config.jar];
      const args = [...base, "run", "--adapter", config.adapter];
      if (config.adapterClass) args.push("--adapter-class", config.adapterClass);
      args.push("--suite", config.suite, "--cases", config.cases, "--output", config.output);
      const command = (tokens) => tokens.map((token, index) => index === 0 ? token : shellQuote(token, config.shell)).join(" ");
      run = command(args);
      serve = command([...base, "serve", "--output", config.output, "--port", "8080"]);
    } catch (error) { errors.push(error.message); }
    return { errors, run: errors.length ? "" : run, serve: errors.length ? "" : serve };
  }
  function failedEvent(event) {
    return Boolean(event.error) || ["error", "failed", "failure"].includes(String(event.status).toLowerCase());
  }
  const api = { exampleCases, validateCases, shellQuote, setupCommands, failedEvent };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.AssayUI = api;
})(globalThis);
