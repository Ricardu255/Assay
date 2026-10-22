/* Shared, dependency-free validation and presentation helpers. */
(function (root) {
  "use strict";
  const exampleCases = '{"id":"EXAMPLE-001","scenario":"direct","input":{"message":"hello agent"},"expected":{"route":"ECHO","keyword":"hello"},"metadata":{"system_constraints":{"max_steps":3,"max_retries":0}}}';
  const httpCases = '{"id":"HTTP-001","scenario":"smoke","input":{"message":"hello agent"},"expected":{"keyword":"hello"}}';
  function httpConfig(config) {
    const errors = [];
    let endpoint = "";
    try {
      const url = new URL(config.endpoint);
      if (!/^https?:$/.test(url.protocol) || !url.hostname || url.username || url.password || url.hash || url.port === "0"
        || /[\u0000-\u001f\u007f]/.test(config.endpoint)) throw new Error();
      endpoint = url.href;
    } catch { errors.push("请填写 HTTP(S) 接口地址，不能包含用户名、密码或 # 片段。"); }
    const path = (key, label) => {
      const value = String(config[key] || "");
      if (!value || value.length > 512 || /[\u0000-\u001f\u007f]/.test(value)
        || value.split(".").some((part) => !part || (/^[0-9]+$/.test(part) && part.length > 9))) errors.push(`${label}应为点分隔的路径，如 data.answer 或 choices.0.message.content。`);
      return value;
    };
    const output = path("outputPath", "响应字段"), expected = path("expectedPath", "期望字段");
    if (!["contains", "eq"].includes(config.operator)) errors.push("请选择包含关键字或完全相等。");
    const token = String(config.tokenEnv || "");
    if (token && !/^[A-Za-z_][A-Za-z0-9_]*$/.test(token)) errors.push("密钥环境变量名只能包含字母、数字和下划线，不能以数字开头。");
    const result = { type: "http", name: "my-agent", endpoint, output_path: output,
      hard_gates: [{ name: "answer", actual: "final_output", operator: config.operator, expected }] };
    if (token) result.bearer_token_env = token;
    return { errors, config: errors.length ? null : result };
  }
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
    if (config.adapter && !config.adapter.endsWith(".jar") && !config.adapter.endsWith(".json") && !name.test(config.adapter)) errors.push("适配器需要完整类名、JAR 路径或 HTTP 配置 JSON 路径。");
    if (config.mode === "http") {
      errors.push(...httpConfig(config).errors);
      if (!config.adapter?.endsWith(".json")) errors.push("HTTP 配置文件路径应以 .json 结尾。");
    }
    if (config.adapterClass && (!config.adapter.endsWith(".jar") || !name.test(config.adapterClass))) errors.push("适配器类名仅用于 JAR，应填写完整类名。");
    let run = "", serve = "";
    try {
      const base = ["java", "-jar", config.jar];
      const args = [...base, "run", "--adapter", config.adapter];
      if (config.adapterClass) args.push("--adapter-class", config.adapterClass);
      args.push("--suite", config.suite, "--cases", config.cases, "--output", config.output);
      const command = (tokens) => tokens.map((token, index) => index === 0 ? token : shellQuote(token, config.shell)).join(" ");
      run = command(args);
      serve = command([...base, "serve", "--output", config.output, "--open-browser"]);
    } catch (error) { errors.push(error.message); }
    return { errors, run: errors.length ? "" : run, serve: errors.length ? "" : serve };
  }
  function failedEvent(event) {
    return Boolean(event.error) || ["error", "failed", "failure"].includes(String(event.status).toLowerCase());
  }
  function gitPath(raw, prefix = true) {
    let value = raw;
    if (raw.startsWith('"')) {
      const quoted = raw.match(/^"((?:\\.|[^"\\])*)"/);
      if (quoted) {
        const bytes = [], encoder = new TextEncoder(), source = quoted[1];
        for (let i = 0; i < source.length;) {
          if (source[i] === "\\") {
            const octal = source.slice(i + 1).match(/^[0-7]{1,3}/);
            if (octal) { bytes.push(parseInt(octal[0], 8)); i += 1 + octal[0].length; continue; }
            const next = source[++i];
            bytes.push(...encoder.encode(({ t: "\t", n: "\n", r: "\r", a: "\x07", b: "\b", v: "\v", f: "\f" })[next] ?? next ?? "\\"));
            i++;
          } else {
            const character = String.fromCodePoint(source.codePointAt(i));
            bytes.push(...encoder.encode(character)); i += character.length;
          }
        }
        value = new TextDecoder().decode(new Uint8Array(bytes));
      }
    } else value = value.split("\t")[0];
    if (value === "/dev/null") return null;
    return prefix ? value.replace(/^[ab]\//, "") : value;
  }
  function parseDiff(text, maxRows = 5000) {
    const files = [];
    let file = null, hunk = null, rows = 0, truncated = false;
    function push(row) {
      if (rows++ < maxRows) file.rows.push(row);
      else truncated = true;
    }
    for (const line of String(text).split("\n")) {
      if (line.startsWith("diff --git ")) {
        if (files.length >= 200) { truncated = true; break; }
        const header = line.slice(11), names = header.match(/^("(?:\\.|[^"\\])*"|a\/.*?) ("(?:\\.|[^"\\])*"|b\/.*)$/);
        file = { oldPath: names ? gitPath(names[1]) : "", newPath: names ? gitPath(names[2]) : "", rows: [], binary: false };
        files.push(file); hunk = null; continue;
      }
      if (!file) {
        if (!line.startsWith("--- ") && !line.startsWith("@@ ")) continue;
        file = { oldPath: "", newPath: "", rows: [], binary: false }; files.push(file);
      }
      const header = line.match(/^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/);
      if (header) {
        hunk = { old: +header[1], left: header[2] === undefined ? 1 : +header[2], next: +header[3], right: header[4] === undefined ? 1 : +header[4] };
        push({ type: "hunk", text: line }); continue;
      }
      if (hunk && line.startsWith("\\ No newline")) { push({ type: "meta", text: line }); continue; }
      if (hunk && (hunk.left > 0 || hunk.right > 0)) {
        const type = line[0], row = { text: line.slice(1) };
        if (type === "-" && hunk.left > 0) { row.type = "deletion"; row.oldLine = hunk.old++; hunk.left--; }
        else if (type === "+" && hunk.right > 0) { row.type = "addition"; row.newLine = hunk.next++; hunk.right--; }
        else if (type === " " && hunk.left > 0 && hunk.right > 0) {
          row.type = "context"; row.oldLine = hunk.old++; row.newLine = hunk.next++; hunk.left--; hunk.right--;
        } else { hunk = null; push({ type: "meta", text: line }); continue; }
        push(row); continue;
      }
      hunk = null;
      if (line.startsWith("--- ")) file.oldPath = gitPath(line.slice(4));
      else if (line.startsWith("+++ ")) file.newPath = gitPath(line.slice(4));
      else if (line.startsWith("rename from ")) { file.oldPath = gitPath(line.slice(12), false); push({ type: "meta", text: line }); }
      else if (line.startsWith("rename to ")) { file.newPath = gitPath(line.slice(10), false); push({ type: "meta", text: line }); }
      else if (line) {
        if (line.startsWith("Binary files ") || line === "GIT binary patch") file.binary = true;
        push({ type: "meta", text: line });
      }
    }
    return { files, truncated };
  }
  function splitRatio(ratio, width) {
    if (!Number.isFinite(width) || width < 700) return 0.5;
    const requested = Number.isFinite(ratio) ? ratio : 0.5;
    return Math.max(260 / width, Math.min(1 - 314 / width, requested));
  }
  const roles = ["improvement", "regression", "holdout"];
  function caseTransitions(payload) {
    const comparisons = (payload && payload.comparisons) || {};
    return roles.map((role) => ({ role, data: (comparisons[role] && comparisons[role].case_transitions) || {} }));
  }
  function regressions(payload) {
    return caseTransitions(payload).flatMap(({ role, data }) => {
      const rows = Array.isArray(data.regressed) ? data.regressed : [];
      return rows.map((item) => ({ role, ...(item && typeof item === "object" ? item : {}) }));
    });
  }
  function failureClusters(rows) {
    const clusters = new Map();
    for (const item of Array.isArray(rows) ? rows : []) {
      const first = (item && item.first_divergence) || {};
      const candidate = first.candidate || {}, baseline = first.baseline || {};
      const event = candidate.module || candidate.action ? candidate : baseline;
      const category = first.category || "unknown";
      const module = event.module || "", action = event.action || "";
      const key = `${category}|${module}|${action}`;
      if (!clusters.has(key)) clusters.set(key, { category, module, action, count: 0, cases: [] });
      const cluster = clusters.get(key);
      cluster.count++;
      if (cluster.cases.length < 5) cluster.cases.push(item.case_id);
    }
    return [...clusters.values()].sort((left, right) => right.count - left.count
      || `${left.category}${left.module}`.localeCompare(`${right.category}${right.module}`));
  }
  function valueText(value) {
    if (value === null || value === undefined) return "—";
    if (typeof value === "string") return value;
    if (typeof value === "number" || typeof value === "boolean") return String(value);
    try { return JSON.stringify(value); } catch { return String(value); }
  }
  function clipped(value, limit = 120) {
    const text = String(value);
    return text.length > limit ? `${text.slice(0, limit)}…` : text;
  }
  function eventLabel(event) {
    const value = event && typeof event === "object" ? event : {};
    const parts = [value.module, value.action].filter(Boolean);
    return parts.length ? parts.join("/") : "未知步骤";
  }
  function changesText(paths, left, right) {
    return (Array.isArray(paths) ? paths : []).map((path, index) => {
      const before = Array.isArray(left) && index < left.length ? valueText(left[index]) : "—";
      const after = Array.isArray(right) && index < right.length ? valueText(right[index]) : "—";
      return `${path}：${clipped(before)} → ${clipped(after)}`;
    }).join("；");
  }
  function divergenceText(first) {
    const data = first && typeof first === "object" ? first : {};
    const step = data.step == null ? "" : `第 ${data.step} 步 `;
    const candidate = data.candidate && typeof data.candidate === "object" ? data.candidate : {};
    const baseline = data.baseline && typeof data.baseline === "object" ? data.baseline : {};
    const where = eventLabel(Object.keys(candidate).length ? candidate : baseline);
    const paths = Array.isArray(data.changed_paths) ? data.changed_paths : [];
    const left = Array.isArray(data.baseline_values) ? data.baseline_values : [];
    const right = Array.isArray(data.candidate_values) ? data.candidate_values : [];
    switch (data.category) {
      case "step_fields":
        return `${step}${where} 字段 ${changesText(paths, left, right) || "发生变化"}`;
      case "step_changed":
        return `${step}从 ${eventLabel(baseline)} 变为 ${eventLabel(candidate)}`;
      case "step_added":
        return `${step}新增 ${where}`;
      case "step_removed":
        return `${step}缺少基线中的 ${where}`;
      case "step_reordered":
        return `${step}步骤顺序变化：${where}`;
      case "step_error":
        return `${step}${where} 报错：${clipped(candidate.error || data.reason || "未知错误")}`;
      case "step_status":
        return `${step}${where} 状态从 ${baseline.status || "未知"} 变为 ${candidate.status || "未知"}`;
      case "final_output":
        return `最终输出从 ${clipped(left.length ? valueText(left[0]) : "—")} 变为 ${clipped(right.length ? valueText(right[0]) : "—")}`;
      case "trace_fields":
        return `Trace 字段 ${changesText(paths, left, right) || "发生变化"}`;
      case "missing_trace":
        return "缺少可读取的 Trace";
      case "no_visible_divergence":
        return "轨迹、最终输出和 Trace 字段一致；失败来自相同数据上的规则或运行完整性问题";
      default:
        return data.reason || "未记录差异原因";
    }
  }
  function reasonText(reason) {
    const text = String(reason ?? "");
    const roleNames = { improvement: "改善集", regression: "回归集", holdout: "留出集" };
    const match = text.match(/^(improvement|regression|holdout)(?:\/([^:]+))?: (.+)$/);
    const role = match ? roleNames[match[1]] : "";
    const name = match && match[2] ? `/${match[2]}` : "";
    const detail = match ? match[3] : text;
    const prefix = role ? `${role}${name}：` : "";
    const exact = {
      "candidate has hard failures": "候选存在硬失败",
      "hard pass rate decreased": "硬通过率下降",
      "soft warnings exceeded the allowed increase": "软告警超过允许增幅",
      "hard pass rate exceeded the allowed drop": "硬通过率超过允许跌幅",
      "exceeded maximum regression": "超过允许的最大退化",
      "required metric is missing": "必需指标缺失",
      "hard pass rate is below the configured minimum": "硬通过率低于配置下限",
      "configured scenario is missing": "配置的场景缺失",
      "candidate satisfied all configured gates": "候选满足全部配置门禁",
      "target identity or version does not match candidate manifest": "目标身份或版本与候选清单不一致",
      "candidate produced no measurable improvement": "候选没有产生可测量的改善",
      "quality gate has no evaluated cases": "质量门禁没有可评测的用例",
      "hard pass rate increased": "硬通过率提升",
      "soft warnings decreased": "软告警减少",
      "minimum improvement not met": "未达到最低改善幅度"
    };
    if (exact[detail]) return prefix + exact[detail];
    if (detail.startsWith("objective improved: ")) {
      return `目标改善：${detail.slice("objective improved: ".length)}`;
    }
    let found = detail.match(/^(\d+) regressed cases exceed the maximum (\d+)$/);
    if (found) return `${prefix}回归用例 ${found[1]} 条，超过上限 ${found[2]} 条`;
    found = detail.match(/^(\d+) critical regressions exceed the maximum (\d+)$/);
    if (found) return `${prefix}关键回归 ${found[1]} 条，超过上限 ${found[2]} 条`;
    found = detail.match(/^quality gate needs at least (\d+) cases, found (\d+)$/);
    if (found) return `${prefix}质量门禁至少需要 ${found[1]} 条用例，实际 ${found[2]} 条`;
    found = detail.match(/^scenario has fewer than (\d+) cases$/);
    if (found) return `${prefix}场景样本少于 ${found[1]} 条`;
    found = detail.match(/^hard pass rate ([\d.]+%) is below the minimum ([\d.]+%)$/);
    if (found) return `${prefix}硬通过率 ${found[1]} 低于下限 ${found[2]}`;
    found = detail.match(/^([\d.]+) exceeds the absolute maximum ([\d.]+)$/);
    if (found) return `${prefix}指标 ${found[1]} 超过绝对上限 ${found[2]}`;
    return text;
  }
  function itemCount(value) {
    const parsed = Number(value);
    return Number.isFinite(parsed) && parsed > 0 ? Math.floor(parsed) : 0;
  }
  function rate(caseCount, hardFailures) {
    return caseCount > 0 ? Math.max(0, Math.min(1, (caseCount - hardFailures) / caseCount)) : null;
  }
  function dayKey(value) {
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) return null;
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
  }
  function runTrend(items, days = 7, now = new Date()) {
    const span = Math.max(1, Math.min(31, Number.isFinite(days) ? Math.floor(days) : 7));
    const end = now instanceof Date && !Number.isNaN(now.getTime()) ? now : new Date();
    const buckets = [], byKey = new Map();
    for (let offset = span - 1; offset >= 0; offset--) {
      const date = new Date(end.getFullYear(), end.getMonth(), end.getDate() - offset, 12);
      const key = dayKey(date);
      const bucket = { key, label: key.slice(5), runs: 0, caseCount: 0, hardFailures: 0, softWarnings: 0, failedRuns: 0, passRate: null };
      buckets.push(bucket); byKey.set(key, bucket);
    }
    for (const item of Array.isArray(items) ? items : []) {
      if (!item || item.kind !== "run") continue;
      const bucket = byKey.get(dayKey(item.modified_at));
      if (!bucket) continue;
      const cases = itemCount(item.case_count);
      const failures = Math.min(cases, itemCount(item.hard_failures));
      bucket.runs++;
      bucket.caseCount += cases;
      bucket.hardFailures += failures;
      bucket.softWarnings += itemCount(item.soft_warnings);
      if (item.status === "failed") bucket.failedRuns++;
    }
    for (const bucket of buckets) bucket.passRate = rate(bucket.caseCount, bucket.hardFailures);
    const totals = buckets.reduce((sum, bucket) => ({
      runs: sum.runs + bucket.runs, caseCount: sum.caseCount + bucket.caseCount,
      hardFailures: sum.hardFailures + bucket.hardFailures, softWarnings: sum.softWarnings + bucket.softWarnings,
      failedRuns: sum.failedRuns + bucket.failedRuns
    }), { runs: 0, caseCount: 0, hardFailures: 0, softWarnings: 0, failedRuns: 0 });
    totals.passRate = rate(totals.caseCount, totals.hardFailures);
    return { days: span, buckets, totals };
  }
  function suiteHealth(items, limit = 8) {
    const groups = new Map();
    for (const item of Array.isArray(items) ? items : []) {
      if (!item || item.kind !== "run") continue;
      const suite = String(item.suite || "").trim() || "未命名测试集";
      const cases = itemCount(item.case_count);
      const failures = Math.min(cases, itemCount(item.hard_failures));
      const at = new Date(item.modified_at);
      const valid = !Number.isNaN(at.getTime());
      if (!groups.has(suite)) groups.set(suite, { suite, runs: 0, caseCount: 0, hardFailures: 0, softWarnings: 0, lastAt: null, lastStatus: "unknown", lastSoft: 0, history: [] });
      const group = groups.get(suite);
      group.runs++;
      group.caseCount += cases;
      group.hardFailures += failures;
      group.softWarnings += itemCount(item.soft_warnings);
      group.history.push({ at: valid ? at : null, passRate: rate(cases, failures), status: item.status || "unknown" });
      if (valid && (!group.lastAt || at > group.lastAt)) {
        group.lastAt = at; group.lastStatus = item.status || "unknown"; group.lastSoft = itemCount(item.soft_warnings);
      }
    }
    const rows = [...groups.values()].map((group) => {
      group.history.sort((left, right) => (left.at?.getTime() || 0) - (right.at?.getTime() || 0));
      group.history = group.history.slice(-8);
      const rates = group.history.map((entry) => entry.passRate).filter((value) => value !== null);
      const lastRate = rates.at(-1) ?? null, previousRate = rates.at(-2) ?? null;
      return { ...group, passRate: rate(group.caseCount, group.hardFailures),
        lastPassRate: lastRate, previousPassRate: previousRate,
        delta: lastRate !== null && previousRate !== null ? lastRate - previousRate : null,
        status: group.lastStatus === "passed" && group.lastSoft > 0 ? "warning" : group.lastStatus };
    });
    rows.sort((left, right) => (right.lastAt?.getTime() || 0) - (left.lastAt?.getTime() || 0));
    return rows.slice(0, Math.max(1, Number.isFinite(limit) ? limit : 8));
  }
  function sequenceSteps(value) {
    if (!Array.isArray(value)) return null;
    return value.map((item) => {
      if (Array.isArray(item)) return { group: sequenceSteps(item) };
      if (typeof item === "string") return { key: item, args: {} };
      if (item && typeof item === "object") {
        const key = item.step ?? item.tool ?? (item.module != null || item.action != null
          ? `${item.module ?? ""}/${item.action ?? ""}` : null);
        const rawArgs = item.args && typeof item.args === "object" ? item.args
          : item.fields && typeof item.fields === "object" ? item.fields : {};
        return { key: key === null ? JSON.stringify(item) : String(key), args: rawArgs };
      }
      return { key: String(item), args: {} };
    });
  }
  function ruleStats(payload) {
    const raw = payload && payload.rule_stats;
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return [];
    return Object.entries(raw).map(([name, value]) => {
      const row = value && typeof value === "object" ? value : {};
      const evaluated = Number.isFinite(Number(row.evaluated)) ? Number(row.evaluated) : 0;
      const passed = Number.isFinite(Number(row.passed)) ? Number(row.passed) : 0;
      const passRate = row.pass_rate === null || row.pass_rate === undefined || !Number.isFinite(Number(row.pass_rate))
        ? null : Number(row.pass_rate);
      return { name: String(name), level: String(row.level || "unknown"), evaluated, passed,
        missing: Number.isFinite(Number(row.missing)) ? Number(row.missing) : 0, passRate };
    }).sort((left, right) => (left.passRate ?? 2) - (right.passRate ?? 2)
      || right.evaluated - left.evaluated || left.name.localeCompare(right.name));
  }
  const api = { exampleCases, httpCases, httpConfig, validateCases, shellQuote, setupCommands, failedEvent, parseDiff, splitRatio, caseTransitions, regressions, failureClusters, divergenceText, reasonText, runTrend, suiteHealth, ruleStats, sequenceSteps };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.AssayUI = api;
})(globalThis);
