"use strict";

const sections = [
  ["overview", "总览", "▦"], ["setup", "开始评测", "+"], ["run", "运行记录", "▤"], ["evolution", "版本演化", "⇄"],
  ["auto", "自动演化", "⟳"], ["audit", "范围审计", "◇"], ["release", "发布验证", "✓"]
];
const labels = {
  passed: "通过", failed: "失败", warning: "软告警", accept: "接受", reject: "拒绝", rollback: "回退", ok: "成功", success: "成功", error: "错误", failure: "失败",
  completed: "已完成", running: "运行中", unknown: "未知", budget_exhausted: "轮次用尽",
  time_budget_exhausted: "时间用尽", evolver_call_budget_exhausted: "调用预算用尽",
  no_acceptable_candidate: "无合格候选", no_candidates: "无候选", review_required: "需要复核",
  insufficient_evidence: "证据不足", no_obvious_issue: "未发现明显问题", clear_out_of_scope: "明确越界",
  suspected_out_of_scope: "疑似越界", reasonable_related: "合理关联", low: "低风险", medium: "中风险", high: "高风险",
  improvement: "改善集", regression: "回归集", holdout: "留出集"
};
const state = { remote: [], imports: [], warnings: [], section: "overview", query: "", stack: [], request: 0,
  tab: "cases", caseFilter: "all", caseQuery: "", casePage: 0, caseIndex: 0, traceFilter: "all" };
const setup = { mode: "example", shell: "powershell", jar: "target/assay.jar", adapter: "io.assay.examples.EchoAdapter",
  adapterClass: "", cases: "examples/cases.example.jsonl", suite: "smoke", output: ".assay/runs",
  validation: AssayUI.validateCases(AssayUI.exampleCases), filename: "内置示例", fileRequest: 0 };
const view = document.getElementById("view");
const $ = (id) => document.getElementById(id);
const array = (value) => Array.isArray(value) ? value : [];
const object = (value) => value && typeof value === "object" && !Array.isArray(value) ? value : {};
const number = (value) => typeof value === "number" && Number.isFinite(value) ? value : null;
const escape = (value) => String(value ?? "").replace(/[&<>"']/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[character]));
const json = (value) => escape(JSON.stringify(value ?? null, null, 2));
const count = (value) => number(value) ?? 0;
const integer = (value) => number(value) === null ? "—" : value.toLocaleString("zh-CN");
const percent = (value) => number(value) === null ? "—" : `${(value * 100).toFixed(1)}%`;
const decimal = (value, suffix = "") => number(value) === null ? "—" : `${value.toLocaleString("zh-CN", { maximumFractionDigits: 3 })}${suffix}`;
const delta = (value, percentage = false) => number(value) === null ? "—" : `${value > 0 ? "+" : ""}${percentage ? percent(value) : decimal(value)}`;
const kindName = (kind) => sections.find((section) => section[0] === kind)?.[1] || kind;
const current = () => state.stack.at(-1);
const items = () => [...state.imports, ...state.remote];
const badge = (value) => `<span class="badge ${/^[a-z_]+$/.test(String(value)) ? escape(value) : "neutral"}">${escape(labels[value] || value || "未知")}</span>`;
const date = (value) => {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? "—" : parsed.toLocaleString("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
};

function toast(message) {
  $("toast").textContent = message;
  $("toast").hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { $("toast").hidden = true; }, 4500);
}

async function request(path) {
  const response = await fetch(path, { cache: "no-store" });
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || `请求失败 (${response.status})`);
  return body;
}

async function refresh() {
  $("refresh-button").disabled = true;
  try {
    const catalog = await request("/api/catalog");
    state.remote = array(catalog.items);
    state.warnings = array(catalog.warnings);
    $("source-path").textContent = `报告目录：${catalog.root}`;
    $("refresh-time").textContent = `上次刷新 ${new Date().toLocaleTimeString("zh-CN")}`;
    if (state.stack.length) {
      const id = state.stack[0].item.id;
      if (state.remote.some((item) => item.id === id)) await openArtifact(id);
      else render();
    } else render();
  } catch (error) {
    toast(`读取失败：${error.message}`);
    if (!items().length) view.innerHTML = `<div class="error-box">无法读取报告：${escape(error.message)}</div>${empty()}`;
  } finally {
    $("refresh-button").disabled = false;
  }
}

function renderNavigation() {
  $("navigation").innerHTML = sections.map(([kind, title, icon]) => `<button class="nav-button ${state.section === kind ? "active" : ""}" data-section="${kind}" title="${title}" ${state.section === kind ? 'aria-current="page"' : ""}>
    <span class="nav-icon" aria-hidden="true">${icon}</span><span class="nav-name">${title}</span>
    ${["overview", "setup"].includes(kind) ? "" : `<span class="nav-count">${items().filter((item) => item.kind === kind).length}</span>`}</button>`).join("");
  $("section-label").textContent = kindName(state.section);
}

function heading(title, subtitle, eyebrow = "EVALUATION WORKSPACE", actions = "") {
  return `<div class="page-heading"><div><div class="eyebrow">${escape(eyebrow)}</div><h1>${escape(title)}</h1><p class="subtitle">${escape(subtitle)}</p></div>${actions}</div>`;
}

function stat(title, value, note, color = "") {
  return `<div class="stat"><div class="stat-label">${escape(title)}</div><div class="stat-value ${color}">${escape(value)}</div><div class="stat-note">${escape(note)}</div></div>`;
}

function empty(title = "还没有评测结果", description = "运行评测后点击刷新，或导入已有 JSON 报告。") {
  return `<div class="empty"><div class="empty-symbol" aria-hidden="true">▤</div><h2>${escape(title)}</h2><p>${escape(description)}</p></div>`;
}

function warnings() {
  return state.warnings.length ? `<details class="warnings"><summary>${state.warnings.length} 份报告暂时无法读取</summary><ul>${state.warnings.map((warning) => `<li>${escape(warning)}</li>`).join("")}</ul></details>` : "";
}

function filteredItems(kind) {
  const query = state.query.toLocaleLowerCase();
  return items().filter((item) => (!kind || item.kind === kind)
    && `${item.name} ${item.path} ${item.status} ${item.suite || ""}`.toLocaleLowerCase().includes(query));
}

function artifactTable(records, limit = Infinity) {
  if (!records.length) return empty("没有匹配的记录", "切换分类、清空搜索，或导入一份报告。");
  return `<div class="table-wrap"><table><thead><tr><th>记录</th><th>类型</th><th>结果</th><th>用例 / 版本</th><th>更新时间</th></tr></thead><tbody>
    ${records.slice(0, limit).map((item) => `<tr><td><button class="record-name" data-artifact="${escape(item.id)}">${escape(item.name)}</button><div class="record-path">${escape(item.path)}</div></td>
      <td class="muted">${escape(kindName(item.kind))}${item.suite ? `<div class="record-path">${escape(item.suite)}</div>` : ""}</td>
      <td>${badge(item.status === "passed" && count(item.soft_warnings) > 0 ? "warning" : item.status)}</td>
      <td class="number">${item.kind === "run" ? `${integer(item.case_count)} <span class="muted">条</span>` : item.current_version ? escape(item.current_version) : "—"}</td>
      <td class="muted number">${escape(date(item.modified_at))}</td></tr>`).join("")}</tbody></table></div>`;
}

function overview() {
  const runs = items().filter((item) => item.kind === "run");
  const total = runs.reduce((sum, item) => sum + count(item.case_count), 0);
  const failures = runs.reduce((sum, item) => sum + count(item.hard_failures), 0);
  const soft = runs.reduce((sum, item) => sum + count(item.soft_warnings), 0);
  const passedRuns = runs.filter((item) => item.status === "passed" && !count(item.soft_warnings)).length;
  const warnedRuns = runs.filter((item) => item.status === "passed" && count(item.soft_warnings)).length;
  const failedRuns = runs.filter((item) => item.status === "failed").length;
  const denominator = Math.max(1, runs.length);
  const recent = filteredItems().slice(0, 8);
  return `${heading("评测工作台", "查看每一次运行，追溯失败证据，对比候选版本的实际变化。", "EVALUATION WORKSPACE", '<button class="button primary" data-section="setup">开始评测 →</button>')}${warnings()}
    <div class="stats">${stat("评测运行", integer(runs.length), "包含基线与候选的独立运行")}
      ${stat("硬门禁通过率", total ? percent((total - failures) / total) : "—", `${integer(total)} 条已记录用例`, "positive")}
      ${stat("硬失败用例", integer(failures), "需要优先关注的执行或门禁失败", failures ? "negative" : "")}
      ${stat("复核信号", integer(soft), "软告警与人工复核候选信号", soft ? "warning-text" : "")}</div>
    <div class="grid"><section class="panel"><div class="panel-head"><h2>最近记录</h2><span class="count-chip">${items().length} 份报告</span></div>${recent.length ? artifactTable(recent) : empty()}</section>
      <section class="panel"><div class="panel-head"><h2>运行结果分布</h2><span class="muted">按运行计数</span></div><div class="panel-body">
        <div class="stat-value">${total ? percent((total - failures) / total) : "—"}<span class="muted"> 硬门禁通过</span></div>
        <svg class="status-chart" viewBox="0 0 300 12" preserveAspectRatio="none" role="img" aria-label="通过 ${passedRuns}，软告警 ${warnedRuns}，失败 ${failedRuns}">
          <rect width="300" height="12" fill="#edf1f5"/><rect width="${300 * passedRuns / denominator}" height="12" fill="#48a899"/>
          <rect x="${300 * passedRuns / denominator}" width="${300 * warnedRuns / denominator}" height="12" fill="#e8bd69"/>
          <rect x="${300 * (passedRuns + warnedRuns) / denominator}" width="${300 * failedRuns / denominator}" height="12" fill="#df7b85"/></svg>
        ${[["seg-pass", "全部通过", passedRuns], ["seg-warning", "通过但有软告警", warnedRuns], ["seg-fail", "硬门禁失败", failedRuns]].map(([color, title, value]) => `<div class="legend-row"><span class="legend-label"><span class="legend-dot ${color}"></span>${title}</span><strong class="number">${value}</strong></div>`).join("")}
        <p class="mini-note">按扫描到的运行汇总。不同版本与数据集的运行分别计数。</p></div></section></div>
    <div class="artifact-counts">${sections.slice(3).map(([kind, title]) => `<button class="kind-tile" data-section="${kind}">${title}<strong>${items().filter((item) => item.kind === kind).length} <span class="muted">份报告</span></strong></button>`).join("")}</div>`;
}

function setupPage() {
  const field = (key, title, note = "") => `<label class="form-field">${title}<input data-setup="${key}" value="${escape(setup[key])}" autocomplete="off">${note ? `<small>${note}</small>` : ""}</label>`;
  return `${heading("开始一次评测", "先跑通内置示例，再换成你自己的 Agent。命令在项目根目录的终端中运行。")}
    <div class="setup-grid"><section class="panel"><div class="panel-head"><h2>1 · 选择适配器</h2></div><div class="panel-body form-grid">
      <label class="form-field full">接入方式<select id="setup-mode"><option value="example" ${setup.mode === "example" ? "selected" : ""}>内置 Echo 示例（无需 API Key）</option><option value="custom" ${setup.mode === "custom" ? "selected" : ""}>自定义 Agent</option></select></label>
      ${field("adapter", "适配器类名或 JAR 路径")}${field("adapterClass", "JAR 内的类名（可选）", "JAR 已注册单个服务实现时可留空。")}
      <div class="full model-note">自定义适配器实现 ProjectAdapterFactory：调用 Agent、转换 Trace、声明硬门禁与软质量规则。可参考项目内的 src/main/java/io/assay/examples/EchoAdapter.java。适配器将在你运行命令时加载。</div>
    </div></section><section class="panel"><div class="panel-head"><h2>2 · 检查测试集</h2></div><div class="panel-body form-grid">
      ${field("cases", "JSONL 文件路径", "选择文件只检查内容；请确认此处是它在电脑上的实际路径。")}${field("suite", "测试集名称")}
      <label class="form-field full">选择 JSONL 文件进行检查<input type="file" id="setup-cases" accept=".jsonl,.ndjson,application/x-ndjson,text/plain"></label>
      <div id="setup-validation" class="full" aria-live="polite"></div>
      <details class="full"><summary>查看一行一个用例的格式</summary><pre>${escape(AssayUI.exampleCases)}</pre></details>
    </div></section></div>
    <section class="panel section-gap"><div class="panel-head"><h2>3 · 生成运行命令</h2><span class="muted">复制到终端运行</span></div><div class="panel-body form-grid">
      <label class="form-field">终端<select data-setup="shell"><option value="powershell" ${setup.shell === "powershell" ? "selected" : ""}>Windows PowerShell</option><option value="bash" ${setup.shell === "bash" ? "selected" : ""}>Bash / Zsh</option></select></label>
      ${field("jar", "框架 JAR 路径")}${field("output", "报告输出目录")}
      <div id="setup-commands" class="full" aria-live="polite"></div>
    </div></section><section class="panel section-gap"><div class="panel-head"><h2>4 · 查看报告</h2></div><div class="panel-body"><p class="subtitle">首次使用先运行 mvn -q package。评测命令会打印 report.md 的位置；运行完成后，使用上方查看命令启动界面。若已经在查看同一输出目录，直接刷新结果。</p><button class="button secondary section-gap" data-section="run">查看运行记录 →</button></div></section>`;
}

function updateSetup() {
  const check = setup.validation;
  $("setup-validation").innerHTML = !check ? '<p class="muted">请选择测试集，检查后即可复制命令。</p>' :
    `${badge(check.errors.length ? "failed" : "passed")} <span class="muted">${escape(setup.filename)} · ${check.count} 条有效用例 · ${check.scenarios.length} 个场景</span>
    ${check.errors.length ? `<ul class="validation-errors">${check.errors.slice(0, 20).map((error) => `<li>${escape(error)}</li>`).join("")}</ul>` : ""}
    ${check.warnings.length ? `<details class="warnings"><summary>${check.warnings.length} 条提示</summary><ul>${check.warnings.slice(0, 20).map((warning) => `<li>${escape(warning)}</li>`).join("")}</ul></details>` : ""}`;
  const commands = AssayUI.setupCommands(setup), errors = [...commands.errors];
  if (!check) errors.push("尚未检查测试集。");
  else if (check.errors.length) errors.push("请先修正测试集错误。");
  $("setup-commands").innerHTML = errors.length ? `<ul class="validation-errors">${errors.map((error) => `<li>${escape(error)}</li>`).join("")}</ul>` :
    `<div class="command-head"><strong>运行评测</strong><button class="button secondary" data-setup-copy="run">复制运行命令</button></div><pre>${escape(commands.run)}</pre>
    <div class="command-head section-gap"><strong>启动报告界面</strong><button class="button secondary" data-setup-copy="serve">复制查看命令</button></div><pre>${escape(commands.serve)}</pre><p class="mini-note">启动后访问 http://127.0.0.1:8080/；按 Ctrl+C 关闭服务。</p>`;
}

function listing() {
  const records = filteredItems(state.section);
  return `${heading(kindName(state.section), "选择一份报告，展开指标、规则结果与原始证据。")}${warnings()}
    <section class="panel"><div class="panel-head"><h2>报告记录</h2><span class="count-chip">${records.length} 份</span></div>${artifactTable(records)}</section>`;
}

function detailHeader(context) {
  const payload = context.payload;
  const status = payload.decision || payload.conclusion || (payload.status === "passed" && count(payload.soft_warnings) ? "warning" : payload.status);
  return `<button class="link-button back" data-back>← ${state.stack.length > 1 ? "返回上一级报告" : "返回记录列表"}</button>
    ${heading(context.item.name, context.item.path || "当前报告内的嵌套运行", kindName(context.item.kind),
      `<div class="detail-actions">${badge(status)}<button class="button secondary" data-download>下载 JSON</button></div>`)}`;
}

function caseStatus(result) {
  return result.hard_pass !== true ? "failed" : count(result.soft_warning_count) > 0 ? "warning" : "passed";
}

function filteredCases() {
  const query = state.caseQuery.toLocaleLowerCase();
  return array(current().payload.results).map((result, index) => ({ result, index })).filter(({ result }) => {
    const item = object(result.case);
    return (state.caseFilter === "all" || caseStatus(result) === state.caseFilter)
      && `${item.case_id} ${item.scenario || ""}`.toLocaleLowerCase().includes(query);
  });
}

function renderCaseRows() {
  const cases = filteredCases();
  const lastPage = Math.max(0, Math.ceil(cases.length / 50) - 1);
  state.casePage = Math.min(state.casePage, lastPage);
  const pageCases = cases.slice(state.casePage * 50, (state.casePage + 1) * 50);
  if (!pageCases.some(({ index }) => index === state.caseIndex)) state.caseIndex = pageCases[0]?.index ?? -1;
  $("case-rows").innerHTML = pageCases.map(({ result, index }) => `<tr class="case-row ${state.caseIndex === index ? "selected" : ""}" data-case="${index}">
    <td><button class="record-name" data-case="${index}">${escape(object(result.case).case_id)}</button><div class="record-path">${escape(object(result.case).scenario || "default")}</div></td>
    <td>${badge(caseStatus(result))}</td><td class="number">${array(object(result.trace).events).length}</td><td class="number">${count(result.soft_warning_count)}</td></tr>`).join("");
  $("case-count").innerHTML = `<span>${cases.length} 条匹配 · 每页最多 50 条</span><div class="pager"><button class="link-button" data-case-page="-1" ${state.casePage === 0 ? "disabled" : ""}>上一页</button><span>${state.casePage + 1} / ${lastPage + 1}</span><button class="link-button" data-case-page="1" ${state.casePage >= lastPage ? "disabled" : ""}>下一页</button></div>`;
  renderTrace();
}

function details(title, value, open = false) {
  return `<details ${open ? "open" : ""}><summary>${escape(title)}</summary><pre>${json(value)}</pre></details>`;
}

function renderTrace() {
  const result = array(current().payload.results)[state.caseIndex];
  if (!result) { $("trace-detail").innerHTML = empty("没有匹配的用例", "调整状态或搜索条件。"); return; }
  const trace = object(result.trace);
  const checks = array(result.checks);
  const events = array(trace.events);
  const review = object(result.llm_review);
  const failures = events.filter(AssayUI.failedEvent).length;
  const visibleEvents = events.map((event, index) => ({ event, index })).filter(({ event }) => state.traceFilter !== "failed" || AssayUI.failedEvent(event));
  $("trace-detail").innerHTML = `<div class="trace-title"><strong>${escape(object(result.case).case_id)}</strong>${badge(caseStatus(result))}</div><div class="trace-content">
    <dl class="metadata"><dt>Trace ID</dt><dd>${escape(trace.trace_id || "无 Trace")}</dd><dt>目标版本</dt><dd>${escape([trace.target_id, trace.target_version].filter(Boolean).join(" · ") || "—")}</dd></dl>
    ${result.error ? `<div class="error-box section-gap">${escape(result.error)}</div>` : ""}
    <h3 class="section-gap">执行时间线 <span class="muted">${events.length} 步 · ${failures} 步失败</span></h3>
    ${events.length ? `<div class="trace-tools"><div class="button-group"><button class="button secondary" data-trace-filter="all" aria-pressed="${state.traceFilter === "all"}">全部步骤</button><button class="button secondary" data-trace-filter="failed" aria-pressed="${state.traceFilter === "failed"}">仅失败</button></div><button class="link-button" data-jump-error ${failures ? "" : "disabled"}>定位首个失败</button><button class="link-button" data-expand-events>展开全部</button></div>
    ${visibleEvents.map(({ event, index }) => `<details class="trace-event ${AssayUI.failedEvent(event) ? "error" : ""}" data-event="${index}" ${AssayUI.failedEvent(event) ? "open" : ""}><summary><span class="step-number">${index + 1}</span><span class="step-main"><strong>${escape(event.module || "未标注模块")}</strong><span class="step-action">${escape(event.action || "未标注动作")}</span></span>${badge(event.status || "unknown")}<span class="muted number">${decimal(event.duration_ms, " ms")}</span></summary><div class="event-body">
      ${event.error ? `<p class="step-error">${escape(event.error)}</p>` : ""}<div class="command-head"><strong>步骤字段</strong><button class="link-button" data-copy-event="${index}">复制步骤 JSON</button></div><pre>${json(event.fields || {})}</pre>
      ${Object.keys(object(event.fields)).length ? "" : '<p class="mini-note">适配器未记录该步骤的额外字段。</p>'}</div></details>`).join("") || '<p class="muted">没有执行失败步骤；规则未通过的证据请查看下方规则检查。</p>'}` : `<p class="muted">${result.trace ? "本次 Trace 没有步骤记录。" : "执行未产生可读取的 Trace。"}</p>`}
    <h3 class="section-gap">规则检查</h3>${checks.length ? checks.map((check) => `<div class="check"><div class="check-head">${badge(check.passed ? "passed" : check.level === "hard" ? "failed" : "warning")}<strong>${escape(check.name)}</strong><span class="muted">${escape({ hard: "硬门禁", soft: "软质量", candidate: "复核信号" }[check.level] || check.level)}</span></div><p>${escape(check.message || check.layer || "")}</p>${details("实际值与期望值", { actual: check.actual, expected: check.expected, suspected_modules: check.suspected_modules })}</div>`).join("") : '<p class="muted">没有规则结果。</p>'}
    ${details("最终输出", trace.final_output, true)}${details("输入与期望", { input: object(result.case).payload, expected: object(result.case).expected })}${details("Trace 字段与反馈", { fields: trace.fields, feedback: trace.feedback })}
    ${Object.keys(review).length ? `<div class="model-note"><strong>模型建议 · 非权威</strong><p>${escape(review.summary || review.error || "查看建议详情")}</p>${details("建议详情", review)}</div>` : ""}</div>`;
}

function interval(successes, total) {
  if (!total) return [0, 0];
  const z = 1.959963984540054, rate = successes / total, denominator = 1 + z * z / total;
  const centre = rate + z * z / (2 * total), margin = z * Math.sqrt((rate * (1 - rate) + z * z / (4 * total)) / total);
  return [(centre - margin) / denominator, (centre + margin) / denominator];
}

function scenarioTable(results) {
  const groups = new Map();
  for (const result of results) {
    const name = object(result.case).scenario || "default";
    const stats = groups.get(name) || { total: 0, passed: 0, soft: 0 };
    stats.total++; stats.passed += result.hard_pass === true ? 1 : 0; stats.soft += count(result.soft_warning_count);
    groups.set(name, stats);
  }
  if (!groups.size) return empty("没有场景数据");
  return `<div class="table-wrap"><table><thead><tr><th>场景</th><th>样本数</th><th>硬门禁通过率</th><th>95% 置信区间</th><th>软告警</th></tr></thead><tbody>${[...groups.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([name, stats]) => {
    const [low, high] = interval(stats.passed, stats.total);
    return `<tr><td>${escape(name)}</td><td class="number">${stats.passed} / ${stats.total}</td><td class="positive number">${percent(stats.passed / stats.total)}</td>
      <td><svg class="scenario-ci" viewBox="0 0 150 20" role="img" aria-label="${percent(low)} 至 ${percent(high)}"><line x1="5" y1="10" x2="145" y2="10" stroke="#edf0f5" stroke-width="3"/><line x1="${5 + low * 140}" y1="10" x2="${5 + high * 140}" y2="10" stroke="#7bb9aa" stroke-width="4"/><circle cx="${5 + stats.passed / stats.total * 140}" cy="10" r="4" fill="#178272"/></svg><span class="muted">${percent(low)} – ${percent(high)}</span></td><td class="number">${stats.soft}</td></tr>`;
  }).join("")}</tbody></table></div>`;
}

function renderRun(context) {
  const payload = context.payload, results = array(payload.results);
  const failures = results.filter((result) => result.hard_pass !== true).length;
  const soft = results.reduce((sum, result) => sum + count(result.soft_warning_count), 0);
  const header = `${detailHeader(context)}<div class="stats">${stat("用例数量", integer(results.length), payload.suite || "当前数据集")}${stat("硬门禁通过率", results.length ? percent((results.length - failures) / results.length) : "—", `${results.length - failures} 条通过`, "positive")}${stat("硬失败", integer(failures), "规则或执行失败", failures ? "negative" : "")}${stat("复核信号", integer(soft), "软告警与复核候选", soft ? "warning-text" : "")}</div>
    <div class="tabs">${[["cases", "用例与 Trace"], ["scenarios", "场景分布"], ["raw", "原始数据"]].map(([tab, title]) => `<button class="tab ${state.tab === tab ? "active" : ""}" data-tab="${tab}">${title}</button>`).join("")}</div>`;
  if (state.tab === "raw") return `${header}<pre class="raw-data">${json(payload)}</pre>`;
  if (state.tab === "scenarios") return `${header}<section class="panel"><div class="panel-head"><h2>场景通过率</h2><span class="muted">Wilson 95% 区间 · 小样本区间更宽</span></div>${scenarioTable(results)}</section>`;
  return `${header}<div class="case-layout"><section class="panel"><div class="panel-head"><h2>用例明细</h2><span class="muted">选择一条查看证据</span></div><div class="case-tools">
    <input type="search" id="case-search" placeholder="搜索用例或场景" aria-label="搜索用例或场景" value="${escape(state.caseQuery)}"><select id="case-filter" aria-label="筛选用例状态">${[["all", "全部状态"], ["failed", "仅硬失败"], ["warning", "仅软告警"], ["passed", "全部通过"]].map(([value, title]) => `<option value="${value}" ${state.caseFilter === value ? "selected" : ""}>${title}</option>`).join("")}</select></div>
    <div class="table-wrap"><table><thead><tr><th>用例 / 场景</th><th>结果</th><th>步骤</th><th>告警</th></tr></thead><tbody id="case-rows"></tbody></table></div><div class="case-count" id="case-count"></div></section><section class="panel" id="trace-detail"></section></div>`;
}

function coverage(metrics, name) {
  const entry = object(object(metrics.objective_coverage)[name]);
  return number(entry.sample_count) === null ? "未记录" : `${entry.sample_count} / ${entry.case_count}`;
}

function renderEvolution(context) {
  const payload = context.payload, candidate = object(payload.candidate), comparisons = object(payload.comparisons);
  const improvement = object(comparisons.improvement);
  const roles = ["improvement", "regression", "holdout"];
  const objectives = array(object(payload.policy).objectives);
  return `${detailHeader(context)}<div class="stats">${stat("候选版本", candidate.candidate_version || "—", `${candidate.target_type || "目标"} · ${candidate.target_id || "未标注"}`)}${stat("改善集通过率变化", delta(improvement.hard_pass_rate_delta, true), "候选减去基线", count(improvement.hard_pass_rate_delta) >= 0 ? "positive" : "negative")}${stat("改善集告警变化", delta(improvement.soft_warning_delta), "负值表示告警减少", count(improvement.soft_warning_delta) <= 0 ? "positive" : "warning-text")}${stat("配置目标", integer(objectives.length), "必需指标覆盖全部样本")}</div>
    <div class="comparison-grid"><section class="panel"><div class="panel-head"><h2>数据集对比</h2><span class="muted">基线 ${escape(candidate.baseline_version || "—")} → 候选 ${escape(candidate.candidate_version || "—")}</span></div>
      <div class="table-wrap"><table><thead><tr><th>数据集</th><th>基线通过率</th><th>候选通过率</th><th>变化</th><th>延迟变化</th></tr></thead><tbody>${roles.map((role) => {
        const row = object(comparisons[role]);
        return `<tr><td><strong>${labels[role]}</strong><div><button class="link-button" data-run-side="baseline" data-role="${role}">基线明细</button> <span class="muted">/</span> <button class="link-button" data-run-side="candidate" data-role="${role}">候选明细</button></div></td><td class="number">${percent(object(row.baseline).hard_pass_rate)}</td><td class="number">${percent(object(row.candidate).hard_pass_rate)}</td><td class="number ${count(row.hard_pass_rate_delta) < 0 ? "negative" : "positive"}">${delta(row.hard_pass_rate_delta, true)}</td><td class="muted number">${number(row.mean_latency_ms_delta) === null ? "缺失" : delta(row.mean_latency_ms_delta) + " ms"}</td></tr>`;
      }).join("")}</tbody></table></div></section><section class="panel"><div class="panel-head"><h2>判定依据</h2><span class="muted">确定性策略</span></div><div class="panel-body"><ul class="evidence">${array(payload.reasons).map((reason) => `<li>${escape(reason)}</li>`).join("") || '<li>没有记录判定依据。</li>'}</ul></div></section></div>
    ${objectives.length ? `<section class="panel"><div class="panel-head"><h2>目标指标与样本覆盖</h2><span class="muted">缺失指标保留为缺失</span></div><div class="table-wrap"><table><thead><tr><th>数据集 / 目标</th><th>基线</th><th>候选</th><th>基线样本</th><th>候选样本</th></tr></thead><tbody>${roles.flatMap((role) => objectives.map((objective) => {
      const row = object(comparisons[role]), baseline = object(row.baseline), proposed = object(row.candidate);
      return `<tr><td>${labels[role]} · ${escape(objective.name)}<div class="record-path">${escape(objective.metric)} · ${escape(objective.aggregation)}${objective.required ? " · 必需" : ""}</div></td><td class="number">${decimal(object(baseline.objectives)[objective.name])}</td><td class="number">${decimal(object(proposed.objectives)[objective.name])}</td><td class="muted number">${coverage(baseline, objective.name)}</td><td class="muted number">${coverage(proposed, objective.name)}</td></tr>`;
    })).join("")}</tbody></table></div></section>` : ""}
    <section class="panel section-gap"><div class="panel-head"><h2>候选说明与策略</h2></div><div class="panel-body"><p class="muted">${escape(candidate.summary || "未提供候选说明")}</p>${details("候选清单", candidate)}${details("评测策略", payload.policy)}</div></section>`;
}

function renderAuto(context) {
  const payload = context.payload, rounds = array(payload.rounds), usage = object(payload.usage);
  return `${detailHeader(context)}<div class="stats">${stat("当前版本", payload.current_version || "—", "沙箱内的候选版本")}${stat("已记录轮次", integer(rounds.length), "展开候选查看完整演化对比")}${stat("诊断 / 生成调用", integer(usage.evolver_calls), "按演化器调用计数")}${stat("循环状态", labels[payload.status] || payload.status || "—", "结果保留在工作区")}</div>
    ${payload.error ? `<div class="error-box">${escape(payload.error)}</div>` : ""}
    ${rounds.map((round, roundIndex) => `<section class="panel round"><div class="panel-head"><h2>第 ${escape(round.round || roundIndex + 1)} 轮</h2><span class="count-chip">${array(round.candidates).length} 个候选</span></div><div class="panel-body"><p class="round-note">${escape(object(round.diagnosis).summary || "没有诊断说明")}</p>
      ${array(round.candidates).length ? `<div class="table-wrap"><table><thead><tr><th>候选</th><th>版本</th><th>决策</th><th>查看</th></tr></thead><tbody>${array(round.candidates).map((entry, candidateIndex) => `<tr><td>${escape(object(entry.candidate).candidate_id)}<div class="record-path">${escape(object(entry.candidate).summary)}</div></td><td>${escape(object(entry.candidate).candidate_version)}</td><td>${badge(object(entry.evaluation).decision)}</td><td><button class="link-button" data-round="${roundIndex}" data-proposal="${candidateIndex}">对比详情 →</button></td></tr>`).join("")}</tbody></table></div>` : '<p class="muted">本轮未生成候选。</p>'}</div></section>`).join("") || empty("没有已完成的轮次", "可在下方查看预算、检查点与循环的原始状态。")}
    <section class="panel section-gap"><div class="panel-head"><h2>循环记录</h2></div><div class="panel-body">${details("预算与使用情况", { budget: payload.budget, usage: payload.usage })}${details("原始数据", payload)}</div></section>`;
}

function renderAudit(context) {
  const payload = context.payload, findings = array(payload.findings), tests = object(payload.tests);
  return `${detailHeader(context)}<div class="stats">${stat("变更文件", integer(array(payload.changed_files).length), "本次审计覆盖的文件")}${stat("审计发现", integer(findings.length), "按规则与模型证据整理")}${stat("新增 / 删除", `${integer(payload.additions)} / ${integer(payload.deletions)}`, "Git Diff 行数")}${stat("相关测试", labels[tests.status] || tests.status || "未运行", "测试结论与范围结论分别展示")}</div>
    <section class="panel"><div class="panel-head"><h2>任务与验收标准</h2></div><div class="panel-body"><p>${escape(payload.requirement || "未提供任务说明")}</p><ul class="evidence">${array(payload.acceptance_criteria).map((item) => `<li>${escape(item)}</li>`).join("")}</ul></div></section>
    <section class="panel section-gap"><div class="panel-head"><h2>范围发现</h2><span class="muted">保留文件、行号与 Diff 侧别</span></div>${findings.map((finding) => `<article class="finding"><div class="finding-top">${badge(finding.status)}${badge(finding.risk)}<code>${escape(finding.path)}:${escape(finding.line ?? "?")} (${escape(finding.line_side || "target")})</code></div><p>${escape(finding.reason)}</p>${details("证据", finding.evidence)}</article>`).join("") || empty("没有记录范围发现")}</section>
    <section class="panel section-gap"><div class="panel-head"><h2>审计记录</h2></div><div class="panel-body">${details("测试结果", tests)}${details("模型发现的校验与处置", payload.model_finding_trace)}${details("原始数据", payload)}</div></section>`;
}

function renderRelease(context) {
  const payload = context.payload;
  return `${detailHeader(context)}<section class="panel"><div class="panel-head"><h2>发布验证阶段</h2><span class="muted">硬门禁失败后停止后续阶段</span></div><div class="table-wrap"><table><thead><tr><th>阶段</th><th>结果</th><th>用例</th><th>硬失败</th><th>查看</th></tr></thead><tbody>${array(payload.stages).map((stage, index) => `<tr><td>${escape(stage.suite)}</td><td>${badge(stage.status)}</td><td>${integer(stage.case_count)}</td><td>${integer(stage.hard_failures)}</td><td><button class="link-button" data-stage="${index}">运行明细 →</button></td></tr>`).join("")}</tbody></table></div></section>`;
}

function render() {
  renderNavigation();
  const context = current();
  if (!context) {
    view.innerHTML = state.section === "overview" ? overview() : state.section === "setup" ? setupPage() : listing();
    if (state.section === "setup") updateSetup();
    return;
  }
  const renderers = { run: renderRun, evolution: renderEvolution, auto: renderAuto, audit: renderAudit, release: renderRelease };
  view.innerHTML = renderers[context.item.kind](context);
  if (context.item.kind === "run" && state.tab === "cases") renderCaseRows();
}

function resetCaseView(payload) {
  state.tab = "cases"; state.caseFilter = "all"; state.caseQuery = ""; state.casePage = 0; state.traceFilter = "all";
  const firstFailure = array(payload.results).findIndex((result) => result.hard_pass !== true);
  state.caseIndex = firstFailure >= 0 ? firstFailure : 0;
  state.casePage = Math.floor(state.caseIndex / 50);
}

async function openArtifact(id) {
  const item = items().find((entry) => entry.id === id);
  if (!item) return;
  const requestId = ++state.request;
  view.innerHTML = '<div class="empty">正在读取报告…</div>';
  try {
    const payload = item.payload || await request(`/api/artifacts/${encodeURIComponent(id)}`);
    if (requestId !== state.request) return;
    state.section = item.kind;
    state.stack = [{ item, payload }];
    resetCaseView(payload);
    render();
  } catch (error) {
    if (requestId !== state.request) return;
    state.stack = [];
    render();
    toast(`报告读取失败：${error.message}`);
  }
}

function inlineReport(kind, payload) {
  if (!payload || typeof payload !== "object") { toast("本报告没有记录该运行。"); return; }
  const name = payload.run_id || payload.experiment_id || kindName(kind);
  state.stack.push({ item: { kind, name, path: "当前报告内的完整评测结果" }, payload });
  resetCaseView(payload);
  render();
  window.scrollTo({ top: 0, behavior: "smooth" });
}

function importedKind(payload) {
  if (payload.run_id && Array.isArray(payload.results)) return "run";
  if (payload.experiment_id && payload.comparisons) return "evolution";
  if (payload.loop_id && Array.isArray(payload.rounds)) return "auto";
  if (payload.conclusion && Array.isArray(payload.findings)) return "audit";
  if (payload.release_id && Array.isArray(payload.stages)) return "release";
  return null;
}

$("file-input").addEventListener("change", async (event) => {
  let added = 0, first = null;
  for (const file of event.target.files) {
    try {
      if (file.size > 64 * 1024 * 1024) throw new Error("文件超过 64 MiB");
      const payload = JSON.parse(await file.text()), kind = importedKind(payload);
      if (!kind) throw new Error("不支持的报告结构");
      const item = { id: `import-${Date.now()}-${state.imports.length}`, kind, payload, path: `导入 / ${file.name}`,
        name: payload.run_id || payload.experiment_id || payload.loop_id || payload.release_id || payload.requirement || file.name,
        modified_at: new Date(file.lastModified).toISOString(), status: payload.decision || payload.conclusion || payload.status,
        case_count: payload.case_count, hard_failures: payload.hard_failures, soft_warnings: payload.soft_warnings, suite: payload.suite,
        current_version: payload.current_version };
      state.imports.unshift(item); first ||= item; added++;
    } catch (error) { toast(`${file.name}：${error.message}`); }
  }
  event.target.value = "";
  if (first) { await openArtifact(first.id); toast(`已导入 ${added} 份报告，仅在当前页面查看。`); }
});

document.addEventListener("click", async (event) => {
  const target = event.target.closest("button,[data-case]");
  if (!target || target.disabled) return;
  if (target.dataset.section) {
    state.request++; state.section = target.dataset.section; state.stack = []; render();
  } else if (target.dataset.setupCopy) {
    try { await navigator.clipboard.writeText(AssayUI.setupCommands(setup)[target.dataset.setupCopy]); toast("命令已复制。"); }
    catch { toast("复制失败，请在命令框中手动选择并复制。"); }
  } else if (target.dataset.traceFilter) { state.traceFilter = target.dataset.traceFilter; renderTrace(); }
  else if (target.hasAttribute("data-jump-error")) {
    const first = document.querySelector(".trace-event.error");
    if (first) { first.open = true; first.querySelector("summary").focus(); first.scrollIntoView({ block: "center", behavior: "smooth" }); }
  } else if (target.hasAttribute("data-expand-events")) {
    const events = [...document.querySelectorAll(".trace-event")], open = events.some((entry) => !entry.open);
    events.forEach((entry) => { entry.open = open; }); target.textContent = open ? "折叠全部" : "展开全部";
  } else if (target.dataset.copyEvent !== undefined) {
    const result = array(current().payload.results)[state.caseIndex];
    try { await navigator.clipboard.writeText(JSON.stringify(array(object(result.trace).events)[Number(target.dataset.copyEvent)], null, 2)); toast("步骤 JSON 已复制。"); }
    catch { toast("复制失败，请手动选择原始数据复制。"); }
  } else if (target.dataset.artifact) openArtifact(target.dataset.artifact);
  else if (target.hasAttribute("data-back")) {
    state.stack.pop(); if (current()) resetCaseView(current().payload); render();
  } else if (target.dataset.tab) { state.tab = target.dataset.tab; render(); }
  else if (target.dataset.case !== undefined) {
    state.caseIndex = Number(target.dataset.case); renderCaseRows();
  } else if (target.dataset.casePage) { state.casePage += Number(target.dataset.casePage); renderCaseRows(); }
  else if (target.dataset.runSide) {
    inlineReport("run", object(current().payload[`${target.dataset.runSide}_runs`])[target.dataset.role]);
  } else if (target.dataset.proposal !== undefined) {
    inlineReport("evolution", array(object(array(current().payload.rounds)[Number(target.dataset.round)]).candidates)[Number(target.dataset.proposal)]?.evaluation);
  } else if (target.dataset.stage !== undefined) inlineReport("run", array(current().payload.stages)[Number(target.dataset.stage)]);
  else if (target.hasAttribute("data-download") && current()) {
    const url = URL.createObjectURL(new Blob([JSON.stringify(current().payload, null, 2)], { type: "application/json" }));
    const link = document.createElement("a"); link.href = url; link.download = `${current().item.kind}-report.json`; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
});

document.addEventListener("input", (event) => {
  if (event.target.dataset.setup) { setup[event.target.dataset.setup] = event.target.value.trim(); updateSetup(); }
  else if (event.target.id === "global-search") { state.query = event.target.value; if (!state.stack.length) render(); }
  else if (event.target.id === "case-search") { state.caseQuery = event.target.value; state.casePage = 0; renderCaseRows(); }
});
document.addEventListener("change", async (event) => {
  if (event.target.id === "case-filter") { state.caseFilter = event.target.value; state.casePage = 0; renderCaseRows(); }
  else if (event.target.id === "setup-mode") {
    setup.fileRequest++;
    setup.mode = event.target.value; setup.adapterClass = "";
    setup.adapter = setup.mode === "example" ? "io.assay.examples.EchoAdapter" : "";
    setup.cases = setup.mode === "example" ? "examples/cases.example.jsonl" : "";
    setup.validation = setup.mode === "example" ? AssayUI.validateCases(AssayUI.exampleCases) : null;
    setup.filename = "内置示例"; render();
  } else if (event.target.id === "setup-cases") {
    const file = event.target.files[0], requestId = ++setup.fileRequest;
    if (!file) return;
    try {
      if (file.size > 16 * 1024 * 1024) throw new Error("引导页检查上限为 16 MiB；较大测试集请直接使用 CLI。");
      const text = await file.text();
      if (requestId !== setup.fileRequest) return;
      setup.validation = AssayUI.validateCases(text); setup.filename = file.name;
      setup.cases = file.name;
      if (state.section === "setup") { document.querySelector('[data-setup="cases"]').value = setup.cases; updateSetup(); }
    } catch (error) {
      setup.validation = { count: 0, scenarios: [], errors: [error.message], warnings: [] };
      if (state.section === "setup") updateSetup();
    }
  }
});
$("refresh-button").addEventListener("click", refresh);
$("import-button").addEventListener("click", () => $("file-input").click());
document.querySelector(".brand").addEventListener("click", (event) => {
  event.preventDefault(); state.request++; state.section = "overview"; state.stack = []; render();
});
refresh();
