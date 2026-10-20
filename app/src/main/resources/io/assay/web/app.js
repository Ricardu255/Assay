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
  improvement: "改善集", regression: "回归集", holdout: "留出集", not_run: "未运行", related: "相关",
  step_added: "新增步骤", step_removed: "缺失步骤", step_changed: "步骤变更", step_reordered: "步骤顺序",
  step_fields: "字段变化", step_error: "步骤报错", step_status: "状态变化", final_output: "最终输出",
  trace_fields: "Trace 字段", missing_trace: "缺少 Trace", no_visible_divergence: "无可见差异"
};
const state = { remote: [], imports: [], warnings: [], section: "overview", query: "", stack: [], request: 0,
  tab: "cases", caseFilter: "all", caseQuery: "", casePage: 0, caseIndex: 0, traceFilter: "all" };
const setup = { mode: "example", shell: "powershell", jar: "app/target/assay.jar", adapter: "io.assay.examples.EchoAdapter",
  endpoint: "", outputPath: "output", tokenEnv: "", operator: "contains", expectedPath: "keyword",
  adapterClass: "", cases: "examples/cases.example.jsonl", suite: "smoke", output: ".assay/runs",
  validation: AssayUI.validateCases(AssayUI.exampleCases), filename: "内置示例", fileRequest: 0 };
function preference(key, fallback) {
  try { return JSON.parse(localStorage.getItem(`assay.${key}`)) ?? fallback; } catch { return fallback; }
}
function savePreference(key, value) {
  try { localStorage.setItem(`assay.${key}`, JSON.stringify(value)); } catch { /* Storage may be disabled. */ }
}
let preferredSplit = preference("caseSplit", 0.5), traceCollapsed = preference("traceCollapsed", false) === true;
let disposeSplit = () => {}, routeReady = false, restoringRoute = false;
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
const scrollMotion = () => matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth";
$("theme-select").value = AssayTheme.choice();

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
      if (state.remote.some((item) => item.id === id)) await restoreLocation();
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
  return `<div class="page-heading"><div><div class="eyebrow">${escape(eyebrow)}</div><h1 tabindex="-1">${escape(title)}</h1><p class="subtitle">${escape(subtitle)}</p></div>${actions}</div>`;
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
          <rect width="300" height="12" fill="var(--line)"/><rect width="${300 * passedRuns / denominator}" height="12" fill="var(--chart-good)"/>
          <rect x="${300 * passedRuns / denominator}" width="${300 * warnedRuns / denominator}" height="12" fill="var(--chart-warning)"/>
          <rect x="${300 * (passedRuns + warnedRuns) / denominator}" width="${300 * failedRuns / denominator}" height="12" fill="var(--chart-bad)"/></svg>
        ${[["seg-pass", "全部通过", passedRuns], ["seg-warning", "通过但有软告警", warnedRuns], ["seg-fail", "硬门禁失败", failedRuns]].map(([color, title, value]) => `<div class="legend-row"><span class="legend-label"><span class="legend-dot ${color}"></span>${title}</span><strong class="number">${value}</strong></div>`).join("")}
        <p class="mini-note">按扫描到的运行汇总。不同版本与数据集的运行分别计数。</p></div></section></div>
    <div class="artifact-counts">${sections.slice(3).map(([kind, title]) => `<button class="kind-tile" data-section="${kind}">${title}<strong>${items().filter((item) => item.kind === kind).length} <span class="muted">份报告</span></strong></button>`).join("")}</div>`;
}

function setupPage() {
  const field = (key, title, note = "") => `<label class="form-field">${title}<input data-setup="${key}" value="${escape(setup[key])}" autocomplete="off">${note ? `<small>${note}</small>` : ""}</label>`;
  const http = setup.mode === "http";
  return `${heading("开始一次评测", "填写 HTTP 接口 → 下载配置 → 选择测试集 → 复制命令运行。无需编写 Java 适配器。")}
    <div class="setup-grid"><section class="panel"><div class="panel-head"><h2>1 · 选择适配器</h2></div><div class="panel-body form-grid">
      <label class="form-field full">接入方式<select id="setup-mode"><option value="example" ${setup.mode === "example" ? "selected" : ""}>内置 Echo 示例（无需 API Key）</option><option value="http" ${http ? "selected" : ""}>HTTP 接口（推荐，无需写代码）</option><option value="custom" ${setup.mode === "custom" ? "selected" : ""}>Java 类 / JAR 适配器（高级）</option></select></label>
      ${http ? `${field("endpoint", "Agent 接口地址", "同步 POST JSON 接口；请求体直接使用用例的 input。")}${field("outputPath", "回答在响应中的字段", "如 output、data.answer、choices.0.message.content。")}
      ${field("tokenEnv", "Bearer 密钥环境变量名（可选）", "如 AGENT_API_KEY；请勿在此粘贴密钥。无需鉴权时留空。")}
      <label class="form-field">通过条件<select data-setup="operator"><option value="contains" ${setup.operator === "contains" ? "selected" : ""}>回答包含期望关键字</option><option value="eq" ${setup.operator === "eq" ? "selected" : ""}>回答与期望值完全相等</option></select></label>
      ${field("expectedPath", "用例 expected 中的期望字段", "默认 keyword；填写 answer 可检查 expected.answer。")}${field("adapter", "HTTP 配置文件路径", "下载后放在项目根目录；文件名需与这里一致。")}
      <div id="setup-http" class="full" aria-live="polite"></div>` : `${field("adapter", "适配器类名或 JAR 路径")}${field("adapterClass", "JAR 内的类名（可选）", "JAR 已注册单个服务实现时可留空。")}
      <div class="full model-note">自定义适配器实现 ProjectAdapterFactory：调用 Agent、转换 Trace、声明硬门禁与软质量规则。可参考项目内的 app/src/main/java/io/assay/examples/EchoAdapter.java。</div>`}
    </div></section><section class="panel"><div class="panel-head"><h2>2 · 检查测试集</h2></div><div class="panel-body form-grid">
      ${field("cases", "JSONL 文件路径", "选择文件只检查内容；请确认此处是它在电脑上的实际路径。")}${field("suite", "测试集名称")}
      <label class="form-field full">选择 JSONL 文件进行检查<input type="file" id="setup-cases" accept=".jsonl,.ndjson,application/x-ndjson,text/plain"></label>
      <div id="setup-validation" class="full" aria-live="polite"></div>
      ${http ? '<div class="full"><button class="button secondary" data-setup-download="cases">下载 HTTP 测试集模板</button><p class="mini-note">修改 input 为接口需要的完整请求体，修改 expected 为真实期望，再选择修改后的文件进行检查。</p></div>' : ""}
      <details class="full"><summary>查看一行一个用例的格式</summary><pre>${escape(http ? AssayUI.httpCases : AssayUI.exampleCases)}</pre></details>
    </div></section></div>
    <section class="panel section-gap"><div class="panel-head"><h2>3 · 生成运行命令</h2><span class="muted">复制到终端运行</span></div><div class="panel-body form-grid">
      <label class="form-field">终端<select data-setup="shell"><option value="powershell" ${setup.shell === "powershell" ? "selected" : ""}>Windows PowerShell</option><option value="bash" ${setup.shell === "bash" ? "selected" : ""}>Bash / Zsh</option></select></label>
      ${field("output", "报告输出目录")}<details class="full"><summary>高级：JAR 路径与手写配置</summary>${field("jar", "框架 JAR 路径")}<p class="mini-note">HTTP 配置可手动增加 hard_gates / soft_quality 规则；fields 保存完整 JSON 响应，可用 fields.route 等路径检查业务字段。events_path 可接入真实内部步骤。</p></details>
      <div id="setup-commands" class="full"></div>
    </div></section><section class="panel section-gap"><div class="panel-head"><h2>4 · 查看报告</h2></div><div class="panel-body"><p class="subtitle">在项目根目录的终端运行命令。源码版启动器需要 JDK 17+ 和 Maven，会自动构建。完成后会打印 report.md 的位置；若当前界面查看的是同一输出目录，点击顶部刷新即可。否则运行查看命令打开界面。</p><button class="button secondary section-gap" data-section="run">查看运行记录 →</button></div></section>`;
}

function updateSetup() {
  if (setup.mode === "http") {
    const generated = AssayUI.httpConfig(setup);
    $("setup-http").innerHTML = generated.errors.length ? `<ul class="validation-errors">${generated.errors.map((error) => `<li>${escape(error)}</li>`).join("")}</ul>` :
      `<button class="button primary" data-setup-download="config" ${setup.adapter.endsWith(".json") ? "" : "disabled"}>下载 HTTP 配置</button><details class="section-gap"><summary>预览配置（不含密钥）</summary><pre>${json(generated.config)}</pre></details>
      ${setup.tokenEnv ? `<p class="mini-note">运行前，在同一个终端执行下方命令，将占位文字替换为实际密钥。配置与评测命令只保存变量名。</p><pre>${escape(setup.shell === "bash" ? `export ${setup.tokenEnv}='替换为你的密钥'` : `$env:${setup.tokenEnv}='替换为你的密钥'`)}</pre>` : ""}`;
  }
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
    <div class="command-head section-gap"><strong>启动报告界面</strong><button class="button secondary" data-setup-copy="serve">复制查看命令</button></div><pre>${escape(commands.serve)}</pre><p class="mini-note">查看命令会自动打开浏览器；实际地址以终端输出为准，按 Ctrl+C 关闭服务。</p>`;
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
    <td><button class="record-name" data-case="${index}" tabindex="${state.caseIndex === index ? 0 : -1}" ${state.caseIndex === index ? 'aria-current="true"' : ""}>${escape(object(result.case).case_id)}</button><div class="record-path">${escape(object(result.case).scenario || "default")}</div></td>
    <td>${badge(caseStatus(result))}</td><td class="number">${array(object(result.trace).events).length}</td><td class="number">${count(result.soft_warning_count)}</td></tr>`).join("");
  $("case-count").innerHTML = `<span>${cases.length} 条匹配 · 每页最多 50 条</span><div class="pager"><button class="link-button" data-case-page="-1" ${state.casePage === 0 ? "disabled" : ""}>上一页</button><span>${state.casePage + 1} / ${lastPage + 1}</span><button class="link-button" data-case-page="1" ${state.casePage >= lastPage ? "disabled" : ""}>下一页</button></div>`;
  renderTrace();
}

function focusToken(element) {
  if (!element || element === document.body) return null;
  if (element.id) return { id: element.id };
  const data = Object.entries(element.dataset || {});
  return data.length ? { data, tag: element.tagName } : null;
}
function restoreFocus(token) {
  if (!token) return false;
  const element = token.id ? $(token.id) : [...document.querySelectorAll("button,summary,input,select")]
    .find((candidate) => candidate.tagName === token.tag && token.data.every(([key, value]) => candidate.dataset[key] === value));
  if (!element || element.closest("[hidden]")) return false;
  element.focus({ preventScroll: true }); return true;
}
function focusCase() { document.querySelector('#case-rows button[aria-current="true"]')?.focus({ preventScroll: true }); }

function mountSplit() {
  const container = document.querySelector(".case-layout"), handle = $("case-resizer");
  if (!container || !handle) return;
  let drag = null, ratio = preferredSplit;
  const apply = (next) => {
    const width = container.getBoundingClientRect().width;
    ratio = AssayUI.splitRatio(next, width);
    container.style.setProperty("--case-split", `${ratio * 100}%`);
    handle.setAttribute("aria-valuenow", Math.round(ratio * 100));
    handle.setAttribute("aria-valuemin", Math.ceil(260 / Math.max(width, 700) * 100));
    handle.setAttribute("aria-valuemax", Math.floor((1 - 314 / Math.max(width, 700)) * 100));
  };
  const persist = () => { preferredSplit = ratio; savePreference("caseSplit", ratio); };
  const finish = (cancel = false) => {
    if (!drag) return;
    const previous = drag; drag = null;
    if (cancel) apply(previous.ratio); else persist();
    document.documentElement.classList.remove("pane-dragging");
    if (handle.hasPointerCapture(previous.id)) handle.releasePointerCapture(previous.id);
  };
  handle.addEventListener("pointerdown", (event) => {
    if (event.button !== 0) return;
    event.preventDefault(); handle.focus();
    drag = { x: event.clientX, ratio, id: event.pointerId };
    handle.setPointerCapture(event.pointerId);
  });
  handle.addEventListener("pointermove", (event) => {
    if (!drag || Math.abs(event.clientX - drag.x) < 4) return;
    document.documentElement.classList.add("pane-dragging");
    apply(drag.ratio + (event.clientX - drag.x) / container.getBoundingClientRect().width);
  });
  handle.addEventListener("pointerup", () => finish());
  handle.addEventListener("pointercancel", () => finish(true));
  handle.addEventListener("lostpointercapture", () => finish(true));
  handle.addEventListener("dblclick", () => { apply(0.5); persist(); });
  handle.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && drag) { finish(true); return; }
    if (!["ArrowLeft", "ArrowRight", "Home", "End", "Enter"].includes(event.key)) return;
    event.preventDefault();
    apply(event.key === "Enter" ? 0.5 : event.key === "Home" ? 0 : event.key === "End" ? 1 : ratio + (event.key === "ArrowLeft" ? -1 : 1) * (event.shiftKey ? 0.1 : 0.02));
    persist();
  });
  const observer = new ResizeObserver(() => apply(preferredSplit)); observer.observe(container); apply(preferredSplit);
  disposeSplit = () => { finish(true); observer.disconnect(); document.documentElement.classList.remove("pane-dragging"); };
}

function syncLocation(push = false) {
  if (!routeReady || restoringRoute) return;
  const params = new URLSearchParams({ section: state.section });
  const root = state.stack[0], context = current();
  if (root?.item.id && !root.item.id.startsWith("import-")) {
    params.set("report", root.item.id);
    const trail = state.stack.slice(1).map((entry) => entry.trail).filter(Boolean).join(",");
    if (trail) params.set("trail", trail);
    if (context.item.kind === "run") {
      params.set("tab", state.tab); params.set("filter", state.caseFilter);
      if (state.caseQuery) params.set("caseQuery", state.caseQuery);
      const id = object(array(context.payload.results)[state.caseIndex]?.case).case_id;
      if (id) params.set("case", id);
    }
  }
  if (state.query) params.set("q", state.query);
  const hash = `#${params}`;
  if (location.hash !== hash) history[push ? "pushState" : "replaceState"](null, "", hash);
}

async function restoreLocation() {
  const params = new URLSearchParams(location.hash.slice(1));
  restoringRoute = true;
  try {
    state.section = sections.some(([kind]) => kind === params.get("section")) ? params.get("section") : "overview";
    state.query = (params.get("q") || "").slice(0, 1000); $("global-search").value = state.query; state.stack = [];
    const id = params.get("report");
    if (id && state.remote.some((item) => item.id === id)) {
      await openArtifact(id);
      if (state.stack[0]?.item.id !== id) return;
      for (const trail of (params.get("trail") || "").split(",").filter(Boolean).slice(0, 8)) {
        const [type, first, second] = trail.split(":"), payload = current().payload;
        if (type === "run" && ["baseline", "candidate"].includes(first) && ["improvement", "regression", "holdout"].includes(second)) {
          const run = object(payload[`${first}_runs`])[second]; if (!run) break;
          inlineReport("run", run, trail);
        } else if (type === "proposal" && /^\d+$/.test(first) && /^\d+$/.test(second)) {
          const evaluation = array(object(array(payload.rounds)[+first]).candidates)[+second]?.evaluation;
          if (!evaluation) break; inlineReport("evolution", evaluation, trail);
        } else if (type === "stage" && /^\d+$/.test(first)) {
          const run = array(payload.stages)[+first]; if (!run) break; inlineReport("run", run, trail);
        } else break;
      }
      if (current().item.kind === "run") {
        if (["cases", "scenarios", "raw"].includes(params.get("tab"))) state.tab = params.get("tab");
        if (["all", "passed", "warning", "failed"].includes(params.get("filter"))) state.caseFilter = params.get("filter");
        state.caseQuery = (params.get("caseQuery") || "").slice(0, 1000);
        const selected = array(current().payload.results).findIndex((result) => object(result.case).case_id === params.get("case"));
        if (selected >= 0) { state.caseIndex = selected; state.casePage = Math.floor(selected / 50); }
      }
    } else if (id) toast("报告已不在当前目录，请从列表重新选择。");
    render();
  } finally { restoringRoute = false; syncLocation(); }
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
      <td><svg class="scenario-ci" viewBox="0 0 150 20" role="img" aria-label="${percent(low)} 至 ${percent(high)}"><line x1="5" y1="10" x2="145" y2="10" stroke="var(--line)" stroke-width="3"/><line x1="${5 + low * 140}" y1="10" x2="${5 + high * 140}" y2="10" stroke="var(--chart-good)" stroke-width="4"/><circle cx="${5 + stats.passed / stats.total * 140}" cy="10" r="4" fill="var(--teal)"/></svg><span class="muted">${percent(low)} – ${percent(high)}</span></td><td class="number">${stats.soft}</td></tr>`;
  }).join("")}</tbody></table></div>`;
}

function renderRun(context) {
  const payload = context.payload, results = array(payload.results);
  const failures = results.filter((result) => result.hard_pass !== true).length;
  const soft = results.reduce((sum, result) => sum + count(result.soft_warning_count), 0);
  const header = `${detailHeader(context)}<div class="stats">${stat("用例数量", integer(results.length), payload.suite || "当前数据集")}${stat("硬门禁通过率", results.length ? percent((results.length - failures) / results.length) : "—", `${results.length - failures} 条通过`, "positive")}${stat("硬失败", integer(failures), "规则或执行失败", failures ? "negative" : "")}${stat("复核信号", integer(soft), "软告警与复核候选", soft ? "warning-text" : "")}</div>
    <div class="tabs" role="tablist" aria-label="运行详情">${[["cases", "用例与 Trace"], ["scenarios", "场景分布"], ["raw", "原始数据"]].map(([tab, title]) => `<button id="tab-${tab}" role="tab" aria-controls="run-panel" aria-selected="${state.tab === tab}" tabindex="${state.tab === tab ? 0 : -1}" class="tab ${state.tab === tab ? "active" : ""}" data-tab="${tab}">${title}</button>`).join("")}</div>`;
  const panel = `id="run-panel" role="tabpanel" tabindex="0" aria-labelledby="tab-${state.tab}"`;
  if (state.tab === "raw") return `${header}<div ${panel}><pre class="raw-data">${json(payload)}</pre></div>`;
  if (state.tab === "scenarios") return `${header}<section class="panel" ${panel}><div class="panel-head"><h2>场景通过率</h2><span class="muted">Wilson 95% 区间 · 小样本区间更宽</span></div>${scenarioTable(results)}</section>`;
  return `${header}<div ${panel}><div class="case-view-tools"><p class="muted">用例列表支持 ↑ ↓、Home / End 和 PageUp / PageDown。</p><button class="button secondary" data-toggle-trace aria-expanded="${!traceCollapsed}" aria-controls="trace-detail">${traceCollapsed ? "展开 Trace" : "折叠 Trace"}</button></div><div class="case-workspace"><div class="case-layout ${traceCollapsed ? "trace-collapsed" : ""}"><section class="panel" id="case-list"><div class="panel-head"><h2>用例明细</h2><span class="muted">选择一条查看证据</span></div><div class="case-tools">
    <input type="search" id="case-search" placeholder="搜索用例或场景" aria-label="搜索用例或场景" value="${escape(state.caseQuery)}"><select id="case-filter" aria-label="筛选用例状态">${[["all", "全部状态"], ["failed", "仅硬失败"], ["warning", "仅软告警"], ["passed", "全部通过"]].map(([value, title]) => `<option value="${value}" ${state.caseFilter === value ? "selected" : ""}>${title}</option>`).join("")}</select></div>
    <div class="table-wrap"><table><thead><tr><th>用例 / 场景</th><th>结果</th><th>步骤</th><th>告警</th></tr></thead><tbody id="case-rows"></tbody></table></div><div class="case-count" id="case-count"></div></section><div id="case-resizer" class="split-handle" role="separator" tabindex="0" aria-orientation="vertical" aria-controls="case-list trace-detail" aria-label="调整用例与 Trace 宽度，左右键调节，双击或 Enter 平分" aria-valuemin="0" aria-valuemax="100" aria-valuenow="50"></div><section class="panel" id="trace-detail" ${traceCollapsed ? "hidden" : ""}></section></div></div></div>`;
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
  const qualityGates = array(object(payload.policy).quality_gates);
  const regressions = AssayUI.regressions(payload);
  const criticalRegressions = regressions.filter((item) => item.critical).length;
  const ruleRows = roles.flatMap((role) => Object.entries(object(object(comparisons[role]).rule_deltas))
    .map(([name, row]) => ({ role, name, row: object(row) })));
  const latencySamples = (metrics) => {
    const stats = object(object(metrics.metric_coverage).latency_ms);
    return number(stats.sample_count) === null ? "样本未知" : `${stats.sample_count}/${stats.case_count}`;
  };
  const ruleRow = ({ role, name, row }) => {
    const baseline = object(row.baseline), proposed = object(row.candidate);
    const stats = Object.keys(proposed).length ? proposed : baseline;
    const level = { hard: "硬门禁", soft: "软质量", candidate: "复核信号" }[stats.level] || stats.level || "未知";
    const sample = (value) => {
      if (!Object.keys(value).length) return "—";
      const evaluated = number(value.evaluated), missing = number(value.missing);
      return evaluated === null ? "—" : `${evaluated} / ${evaluated + count(missing)}`;
    };
    const deltaValue = number(row.pass_rate_delta);
    const deltaClass = deltaValue === null ? "" : deltaValue < 0 ? "negative" : deltaValue > 0 ? "positive" : "";
    return `<tr><td>${labels[role]}</td><td>${escape(name)}</td><td>${escape(level)}</td><td class="number">${percent(baseline.pass_rate)}</td><td class="number">${percent(proposed.pass_rate)}</td><td class="number ${deltaClass}">${deltaValue === null ? "缺失" : `${deltaValue > 0 ? "+" : ""}${percent(deltaValue)}`}</td><td class="muted number">${sample(baseline)}</td><td class="muted number">${sample(proposed)}</td></tr>`;
  };
  const ruleTable = (rows) => `<div class="table-wrap"><table><thead><tr><th>数据集</th><th>规则</th><th>级别</th><th>基线</th><th>候选</th><th>变化</th><th>基线样本</th><th>候选样本</th></tr></thead><tbody>${rows.map(ruleRow).join("")}</tbody></table></div>`;
  const changedRuleRows = ruleRows.filter(({ row }) => {
    const delta = number(row.pass_rate_delta);
    return delta === null || delta !== 0;
  });
  return `${detailHeader(context)}<div class="stats">${stat("候选版本", candidate.candidate_version || "—", `${candidate.target_type || "目标"} · ${candidate.target_id || "未标注"}`)}${stat("改善集通过率变化", delta(improvement.hard_pass_rate_delta, true), "候选减去基线", count(improvement.hard_pass_rate_delta) >= 0 ? "positive" : "negative")}${stat("改善集告警变化", delta(improvement.soft_warning_delta), "负值表示告警减少", count(improvement.soft_warning_delta) <= 0 ? "positive" : "warning-text")}${stat("回归用例", integer(regressions.length), criticalRegressions ? `其中关键 ${criticalRegressions} 条` : "基线通过、候选失败", regressions.length ? "negative" : "")}</div>
    <div class="comparison-grid"><section class="panel"><div class="panel-head"><h2>数据集对比</h2><span class="muted">基线 ${escape(candidate.baseline_version || "—")} → 候选 ${escape(candidate.candidate_version || "—")}</span></div>
      <div class="table-wrap"><table><thead><tr><th>数据集</th><th>基线通过率</th><th>候选通过率</th><th>变化</th><th>平均延迟变化</th><th>P95 延迟变化</th></tr></thead><tbody>${roles.map((role) => {
        const row = object(comparisons[role]);
        return `<tr><td><strong>${labels[role]}</strong><div><button class="link-button" data-run-side="baseline" data-role="${role}">基线明细</button> <span class="muted">/</span> <button class="link-button" data-run-side="candidate" data-role="${role}">候选明细</button></div></td><td class="number">${percent(object(row.baseline).hard_pass_rate)}</td><td class="number">${percent(object(row.candidate).hard_pass_rate)}</td><td class="number ${count(row.hard_pass_rate_delta) < 0 ? "negative" : "positive"}">${delta(row.hard_pass_rate_delta, true)}</td><td class="muted number">${number(row.mean_latency_ms_delta) === null ? "缺失" : delta(row.mean_latency_ms_delta) + " ms"}</td><td class="muted number">${number(row.p95_latency_ms_delta) === null ? "缺失" : delta(row.p95_latency_ms_delta) + " ms"}<div class="record-path">${latencySamples(object(row.baseline))} → ${latencySamples(object(row.candidate))}</div></td></tr>`;
      }).join("")}</tbody></table></div></section><section class="panel"><div class="panel-head"><h2>判定依据</h2><span class="muted">确定性策略</span></div><div class="panel-body"><ul class="evidence">${array(payload.reasons).map((reason) => `<li>${escape(AssayUI.reasonText(reason))}</li>`).join("") || '<li>没有记录判定依据。</li>'}</ul></div></section></div>
    ${caseTransitionPanels(payload)}
    ${objectives.length ? `<section class="panel"><div class="panel-head"><h2>目标指标与样本覆盖</h2><span class="muted">缺失指标保留为缺失</span></div><div class="table-wrap"><table><thead><tr><th>数据集 / 目标</th><th>基线</th><th>候选</th><th>基线样本</th><th>候选样本</th></tr></thead><tbody>${roles.flatMap((role) => objectives.map((objective) => {
      const row = object(comparisons[role]), baseline = object(row.baseline), proposed = object(row.candidate);
      return `<tr><td>${labels[role]} · ${escape(objective.name)}<div class="record-path">${escape(objective.metric)} · ${escape(objective.aggregation)}${objective.required ? " · 必需" : ""}${objective.maximum != null ? ` · 上限 ${decimal(objective.maximum)}` : ""}</div></td><td class="number">${decimal(object(baseline.objectives)[objective.name])}</td><td class="number">${decimal(object(proposed.objectives)[objective.name])}</td><td class="muted number">${coverage(baseline, objective.name)}</td><td class="muted number">${coverage(proposed, objective.name)}</td></tr>`;
    })).join("")}</tbody></table></div></section>` : ""}
    ${qualityGates.length ? `<section class="panel section-gap"><div class="panel-head"><h2>绝对质量门禁</h2><span class="muted">不依赖基线，只约束候选自身</span></div><div class="table-wrap"><table><thead><tr><th>数据集</th><th>最低硬通过率</th><th>最大回归数</th><th>最大关键回归</th><th>最小样本</th></tr></thead><tbody>${qualityGates.flatMap((gate) => array(gate.roles).map((role) => `<tr><td>${labels[role] || escape(role)}</td><td class="number">${gate.minimum_hard_pass_rate == null ? "—" : percent(gate.minimum_hard_pass_rate)}</td><td class="number">${integer(gate.maximum_task_regressions)}</td><td class="number">${integer(gate.maximum_critical_task_regressions)}</td><td class="number">${integer(gate.minimum_samples)}</td></tr>`)).join("")}</tbody></table></div></section>` : ""}
    ${ruleRows.length ? `<section class="panel section-gap"><div class="panel-head"><h2>规则通过率</h2><span class="muted">只显示相对基线有变化的规则</span></div>
      ${changedRuleRows.length ? ruleTable(changedRuleRows) : '<div class="panel-body"><p class="muted">所有规则的通过率与基线一致。</p></div>'}
      ${ruleRows.length > changedRuleRows.length ? `<div class="panel-body"><details><summary>查看全部 ${ruleRows.length} 条规则（含未变化）</summary>${ruleTable(ruleRows)}</details></div>` : ""}
    </section>` : ""}
    <section class="panel section-gap"><div class="panel-head"><h2>候选说明与策略</h2></div><div class="panel-body"><p class="muted">${escape(candidate.summary || "未提供候选说明")}</p>${details("候选清单", candidate)}${details("评测策略", payload.policy)}</div></section>`;
}

function caseTransitionPanels(payload) {
  const transitions = AssayUI.caseTransitions(payload);
  if (!transitions.some(({ data }) => Object.keys(data).length)) {
    return '<section class="panel section-gap"><div class="panel-head"><h2>用例状态迁移</h2></div><div class="panel-body"><p class="muted">这份报告生成于用例级诊断之前；重新运行演化即可看到回归用例与首错。</p></div></section>';
  }
  const regressions = AssayUI.regressions(payload);

  const summary = `<section class="panel section-gap"><div class="panel-head"><h2>用例状态迁移</h2><span class="muted">基线通过、候选失败才算回归</span></div>
    <div class="table-wrap"><table><thead><tr><th>数据集</th><th>用例</th><th>回归</th><th>恢复</th><th>持续失败</th><th>稳定通过</th></tr></thead><tbody>${transitions.map(({ role, data }) => `<tr><td><strong>${labels[role]}</strong></td><td class="number">${integer(data.case_count)}</td><td class="number">${count(data.regressed_count) ? '<strong class="negative">' + integer(data.regressed_count) + '</strong>' : integer(data.regressed_count)}</td><td class="number">${integer(data.recovered_count)}</td><td class="number">${integer(data.persistent_failure_count)}</td><td class="number">${integer(data.stable_pass_count)}</td></tr>`).join("")}</tbody></table></div></section>`;

  const rows = regressions.map((item) => {
    const first = object(item.first_divergence);
    const checks = array(item.failed_checks).map((check) => escape(check.name || "")).filter(Boolean).join("、") || "—";
    const evidence = `<details><summary>查看证据</summary><pre>${json({
      category: first.category, reason: first.reason, changed_paths: first.changed_paths,
      baseline: first.baseline, candidate: first.candidate,
      baseline_values: first.baseline_values, candidate_values: first.candidate_values
    })}</pre></details>`;
    return `<tr><td>${labels[item.role] || escape(item.role)}</td><td><strong>${escape(item.case_id)}</strong><div class="record-path">${escape(item.scenario || "default")}</div></td><td>${item.critical ? '<span class="badge warning">关键</span>' : "—"}</td><td class="number">${first.step == null ? "—" : integer(first.step)}</td><td>${badge(first.category)}</td><td>${checks}</td><td>${escape(AssayUI.divergenceText(first))}${evidence}</td></tr>`;
  }).join("");
  const regressionPanel = `<section class="panel section-gap"><div class="panel-head"><h2>回归用例与首错</h2><span class="count-chip">${regressions.length} 条</span></div>
    ${regressions.length ? `<div class="table-wrap"><table><thead><tr><th>数据集</th><th>用例 / 场景</th><th>关键</th><th>首错步骤</th><th>类型</th><th>失败规则</th><th>首处差异</th></tr></thead><tbody>${rows}</tbody></table></div>` : '<div class="panel-body"><p class="muted">没有用例从通过变为失败。</p></div>'}</section>`;

  const clusterRows = AssayUI.failureClusters(regressions);
  const clusterPanel = regressions.length ? `<section class="panel section-gap"><div class="panel-head"><h2>失败聚类</h2><span class="muted">按首错类型与步骤归类</span></div>
    <div class="table-wrap"><table><thead><tr><th>类型</th><th>模块 / 动作</th><th>回归数</th><th>用例</th></tr></thead><tbody>${clusterRows.map((cluster) => `<tr><td>${badge(cluster.category)}</td><td>${escape([cluster.module, cluster.action].filter(Boolean).join("/") || "—")}</td><td class="number">${cluster.count}</td><td class="muted">${escape(cluster.cases.join("、"))}${cluster.count > cluster.cases.length ? " …" : ""}</td></tr>`).join("")}</tbody></table></div></section>` : "";

  return summary + regressionPanel + clusterPanel;
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
    <section class="panel section-gap"><div class="panel-head"><h2>范围发现</h2><span class="muted">保留文件、行号与 Diff 侧别</span></div>${findings.map((finding, index) => `<article class="finding"><div class="finding-top">${badge(finding.status)}${badge(finding.risk)}<code>${escape(finding.path)}:${escape(finding.line ?? "?")} (${escape(finding.line_side || "target")})</code>${Number.isInteger(finding.line) && finding.line > 0 ? `<button class="link-button" data-finding="${index}">定位代码行 →</button>` : ""}</div><p>${escape(finding.reason)}</p>${details("证据", finding.evidence)}</article>`).join("") || empty("没有记录范围发现")}</section>
    <section class="panel section-gap"><div class="panel-head"><h2>代码差异</h2><div class="trace-tools"><button class="button secondary" data-load-patch>加载代码差异</button><label class="patch-upload button secondary">选择 diff.patch<input id="audit-patch" type="file" accept=".patch,.diff,text/plain" aria-label="选择审计补丁"></label></div></div><div id="audit-diff" class="panel-body"></div></section>
    <section class="panel section-gap"><div class="panel-head"><h2>审计记录</h2></div><div class="panel-body">${details("测试结果", tests)}${details("模型发现的校验与处置", payload.model_finding_trace)}${details("原始数据", payload)}</div></section>`;
}

function renderAuditPatch(context) {
  const container = $("audit-diff");
  if (!container) return;
  if (!context.patch) {
    container.innerHTML = `<p class="muted">${escape(context.patchError || "加载报告旁的 diff.patch，或选择本地补丁。将核对报告记录的 SHA-256。")}</p>`;
    return;
  }
  const patch = context.patch, parsed = context.parsedPatch || (context.parsedPatch = AssayUI.parseDiff(patch.text));
  const split = context.diffMode === "split";
  container.innerHTML = `<div class="diff-toolbar">${badge(patch.verified ? "passed" : "warning")}<span class="muted">${patch.verified ? "补丁与报告校验一致" : "报告未记录校验值，仅供浏览"}</span><div class="button-group"><button class="button secondary" data-diff-mode="unified" aria-pressed="${!split}">统一视图</button><button class="button secondary" data-diff-mode="split" aria-pressed="${split}">分屏视图</button></div></div>
    ${parsed.truncated ? '<p class="warnings">页面最多展示 5,000 行、200 个文件；完整内容请查看原始 diff.patch。</p>' : ""}
    ${parsed.files.map((file) => `<section class="diff-file" data-old-path="${escape(file.oldPath)}" data-new-path="${escape(file.newPath)}"><h3>${escape(file.newPath || file.oldPath || "未命名文件")}</h3>${file.binary ? '<p class="muted diff-meta">二进制变更，仅显示补丁元信息。</p>' : ""}<div class="table-wrap"><table class="diff-table ${split ? "split" : "unified"}" aria-label="${escape(file.newPath || file.oldPath || "文件")} 的代码差异"><thead><tr>${split ? '<th>旧行</th><th>修改前</th><th>新行</th><th>修改后</th>' : '<th>旧行</th><th>新行</th><th>变更</th>'}</tr></thead><tbody>${file.rows.map((row) => {
      if (["hunk", "meta"].includes(row.type)) return `<tr class="diff-meta"><td colspan="${split ? 4 : 3}">${escape(row.text)}</td></tr>`;
      const attributes = `${row.oldLine == null ? "" : `data-old-line="${row.oldLine}"`} ${row.newLine == null ? "" : `data-new-line="${row.newLine}"`}`;
      return `<tr class="diff-row ${row.type}" ${attributes} tabindex="-1">${split ? `<td class="line-number">${row.oldLine ?? ""}</td><td class="diff-code ${row.type === "addition" ? "diff-blank" : ""}">${row.type === "addition" ? "" : escape(row.text)}</td><td class="line-number">${row.newLine ?? ""}</td><td class="diff-code ${row.type === "deletion" ? "diff-blank" : ""}">${row.type === "deletion" ? "" : escape(row.text)}</td>` : `<td class="line-number">${row.oldLine ?? ""}</td><td class="line-number">${row.newLine ?? ""}</td><td class="diff-code"><span class="diff-sign">${row.type === "addition" ? "+" : row.type === "deletion" ? "−" : " "}</span>${escape(row.text)}</td>`}</tr>`;
    }).join("")}</tbody></table></div></section>`).join("") || '<p class="muted">补丁为空，或没有可展示的 Git 差异。</p>'}`;
}

async function loadAuditPatch(context) {
  if (context.patch) return true;
  if (context.patchLoading) return false;
  if (!context.item.id || context.item.id.startsWith("import-")) { toast("请使用“选择 diff.patch”为导入的报告附上补丁。"); return false; }
  context.patchLoading = true;
  $("audit-diff").innerHTML = '<p class="muted">正在读取并核对补丁…</p>';
  try {
    context.patch = await request(`/api/audit-patch/${encodeURIComponent(context.item.id)}`);
    if (current() === context) renderAuditPatch(context);
    return true;
  } catch (error) {
    context.patchError = `补丁无法读取：${error.message}`;
    if (current() === context) renderAuditPatch(context);
    return false;
  } finally { context.patchLoading = false; }
}

async function locateFinding(index) {
  const context = current(), finding = array(context.payload.findings)[index];
  if (!finding || !await loadAuditPatch(context) || current() !== context) return;
  const file = [...document.querySelectorAll(".diff-file")].find((element) => element.dataset.oldPath === finding.path || element.dataset.newPath === finding.path);
  const key = finding.line_side === "base" ? "oldLine" : "newLine";
  const row = file && [...file.querySelectorAll(".diff-row")].find((element) => Number(element.dataset[key]) === finding.line);
  if (!row) { toast("该文件或行不在补丁可见上下文内；请查看完整 diff.patch。"); return; }
  document.querySelectorAll(".diff-row.highlighted").forEach((element) => element.classList.remove("highlighted"));
  row.classList.add("highlighted"); row.focus({ preventScroll: true }); row.scrollIntoView({ block: "center", behavior: scrollMotion() });
}

function renderRelease(context) {
  const payload = context.payload;
  return `${detailHeader(context)}<section class="panel"><div class="panel-head"><h2>发布验证阶段</h2><span class="muted">硬门禁失败后停止后续阶段</span></div><div class="table-wrap"><table><thead><tr><th>阶段</th><th>结果</th><th>用例</th><th>硬失败</th><th>查看</th></tr></thead><tbody>${array(payload.stages).map((stage, index) => `<tr><td>${escape(stage.suite)}</td><td>${badge(stage.status)}</td><td>${integer(stage.case_count)}</td><td>${integer(stage.hard_failures)}</td><td><button class="link-button" data-stage="${index}">运行明细 →</button></td></tr>`).join("")}</tbody></table></div></section>`;
}

function render(pushLocation = false) {
  const token = focusToken(document.activeElement);
  disposeSplit();
  renderNavigation();
  const context = current();
  if (!context) {
    view.innerHTML = state.section === "overview" ? overview() : state.section === "setup" ? setupPage() : listing();
    if (state.section === "setup") updateSetup();
    syncLocation(pushLocation); restoreFocus(token);
    return;
  }
  const renderers = { run: renderRun, evolution: renderEvolution, auto: renderAuto, audit: renderAudit, release: renderRelease };
  view.innerHTML = renderers[context.item.kind](context);
  if (context.item.kind === "run" && state.tab === "cases") renderCaseRows();
  if (context.item.kind === "audit") renderAuditPatch(context);
  if (context.item.kind === "run" && state.tab === "cases") mountSplit();
  syncLocation(pushLocation); restoreFocus(token);
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
  const returnFocus = focusToken(document.activeElement);
  view.innerHTML = '<div class="empty">正在读取报告…</div>';
  try {
    const payload = item.payload || await request(`/api/artifacts/${encodeURIComponent(id)}`);
    if (requestId !== state.request) return;
    state.section = item.kind;
    state.stack = [{ item, payload, returnFocus }];
    resetCaseView(payload);
    render(true); view.querySelector("h1")?.focus({ preventScroll: true });
  } catch (error) {
    if (requestId !== state.request) return;
    state.stack = [];
    render();
    toast(`报告读取失败：${error.message}`);
  }
}

function inlineReport(kind, payload, trail) {
  if (!payload || typeof payload !== "object") { toast("本报告没有记录该运行。"); return; }
  const name = payload.run_id || payload.experiment_id || kindName(kind);
  state.stack.push({ item: { kind, name, path: "当前报告内的完整评测结果" }, payload, trail, returnFocus: focusToken(document.activeElement) });
  resetCaseView(payload);
  render(true); view.querySelector("h1")?.focus({ preventScroll: true });
  window.scrollTo({ top: 0, behavior: scrollMotion() });
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
    state.request++; state.section = target.dataset.section; state.stack = []; render(true);
    if (!target.closest("#navigation")) view.querySelector("h1")?.focus({ preventScroll: true });
  } else if (target.dataset.setupDownload) {
    const cases = target.dataset.setupDownload === "cases", generated = AssayUI.httpConfig(setup);
    if (!cases && generated.errors.length) { toast("请先修正 HTTP 配置。"); return; }
    const url = URL.createObjectURL(new Blob([cases ? AssayUI.httpCases + "\n" : JSON.stringify(generated.config, null, 2) + "\n"], { type: cases ? "application/x-ndjson" : "application/json" }));
    const link = document.createElement("a"); link.href = url;
    link.download = cases ? "http-cases.jsonl" : setup.adapter.split(/[\\/]/).pop() || "my-agent.http.json";
    link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    toast("请将下载文件放在项目根目录，或将路径改为实际位置。");
  } else if (target.dataset.setupCopy) {
    try { await navigator.clipboard.writeText(AssayUI.setupCommands(setup)[target.dataset.setupCopy]); toast("命令已复制。"); }
    catch { toast("复制失败，请在命令框中手动选择并复制。"); }
  } else if (target.dataset.traceFilter) { state.traceFilter = target.dataset.traceFilter; renderTrace(); }
  else if (target.hasAttribute("data-jump-error")) {
    const first = document.querySelector(".trace-event.error");
    if (first) { first.open = true; first.querySelector("summary").focus(); first.scrollIntoView({ block: "center", behavior: scrollMotion() }); }
  } else if (target.hasAttribute("data-expand-events")) {
    const events = [...document.querySelectorAll(".trace-event")], open = events.some((entry) => !entry.open);
    events.forEach((entry) => { entry.open = open; }); target.textContent = open ? "折叠全部" : "展开全部";
  } else if (target.dataset.copyEvent !== undefined) {
    const result = array(current().payload.results)[state.caseIndex];
    try { await navigator.clipboard.writeText(JSON.stringify(array(object(result.trace).events)[Number(target.dataset.copyEvent)], null, 2)); toast("步骤 JSON 已复制。"); }
    catch { toast("复制失败，请手动选择原始数据复制。"); }
  } else if (target.hasAttribute("data-load-patch")) await loadAuditPatch(current());
  else if (target.dataset.diffMode) { current().diffMode = target.dataset.diffMode; renderAuditPatch(current()); }
  else if (target.dataset.finding !== undefined) await locateFinding(Number(target.dataset.finding));
  else if (target.dataset.artifact) openArtifact(target.dataset.artifact);
  else if (target.hasAttribute("data-back")) {
    const closed = state.stack.pop(); if (current()) resetCaseView(current().payload); render(true);
    if (!restoreFocus(closed?.returnFocus)) view.querySelector("h1")?.focus({ preventScroll: true });
  } else if (target.dataset.tab) { state.tab = target.dataset.tab; render(); }
  else if (target.hasAttribute("data-toggle-trace")) { traceCollapsed = !traceCollapsed; savePreference("traceCollapsed", traceCollapsed); render(); }
  else if (target.dataset.case !== undefined) {
    state.caseIndex = Number(target.dataset.case); renderCaseRows(); syncLocation(); focusCase();
  } else if (target.dataset.casePage) { state.casePage += Number(target.dataset.casePage); renderCaseRows(); syncLocation(); }
  else if (target.dataset.runSide) {
    inlineReport("run", object(current().payload[`${target.dataset.runSide}_runs`])[target.dataset.role], `run:${target.dataset.runSide}:${target.dataset.role}`);
  } else if (target.dataset.proposal !== undefined) {
    inlineReport("evolution", array(object(array(current().payload.rounds)[Number(target.dataset.round)]).candidates)[Number(target.dataset.proposal)]?.evaluation, `proposal:${target.dataset.round}:${target.dataset.proposal}`);
  } else if (target.dataset.stage !== undefined) inlineReport("run", array(current().payload.stages)[Number(target.dataset.stage)], `stage:${target.dataset.stage}`);
  else if (target.hasAttribute("data-download") && current()) {
    const url = URL.createObjectURL(new Blob([JSON.stringify(current().payload, null, 2)], { type: "application/json" }));
    const link = document.createElement("a"); link.href = url; link.download = `${current().item.kind}-report.json`; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
});

document.addEventListener("input", (event) => {
  if (event.target.dataset.setup) { setup[event.target.dataset.setup] = event.target.value.trim(); updateSetup(); }
  else if (event.target.id === "global-search") { state.query = event.target.value; if (!state.stack.length) render(); else syncLocation(); }
  else if (event.target.id === "case-search") { state.caseQuery = event.target.value; state.casePage = 0; renderCaseRows(); syncLocation(); }
});
document.addEventListener("change", async (event) => {
  if (event.target.id === "theme-select") AssayTheme.set(event.target.value);
  else if (event.target.id === "case-filter") { state.caseFilter = event.target.value; state.casePage = 0; renderCaseRows(); syncLocation(); }
  else if (event.target.id === "audit-patch") {
    const context = current(), file = event.target.files[0];
    if (!file) return;
    try {
      if (file.size > 8 * 1024 * 1024) throw new Error("补丁超过 8 MiB 查看上限。");
      const bytes = await file.arrayBuffer(), digest = await crypto.subtle.digest("SHA-256", bytes);
      const hash = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
      const expected = context.payload.diff_sha256;
      if (expected != null && String(expected).toLowerCase() !== hash) throw new Error("补丁 SHA-256 与审计报告不一致。");
      context.patch = { text: new TextDecoder("utf-8", { fatal: true }).decode(bytes), verified: expected != null };
      context.parsedPatch = null;
      if (current() === context) renderAuditPatch(context);
    } catch (error) { toast(error.message); }
    event.target.value = "";
  } else if (event.target.id === "setup-mode") {
    setup.fileRequest++;
    setup.mode = event.target.value; setup.adapterClass = "";
    setup.adapter = setup.mode === "example" ? "io.assay.examples.EchoAdapter" : setup.mode === "http" ? "my-agent.http.json" : "";
    setup.cases = setup.mode === "example" ? "examples/cases.example.jsonl" : setup.mode === "http" ? "http-cases.jsonl" : "";
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
  event.preventDefault(); state.request++; state.section = "overview"; state.stack = []; render(true);
});
document.addEventListener("keydown", (event) => {
  if (event.isComposing || event.keyCode === 229) return;
  if (event.target.matches('#case-rows button[data-case]') && ["ArrowUp", "ArrowDown", "Home", "End", "PageUp", "PageDown"].includes(event.key)) {
    event.preventDefault();
    const cases = filteredCases(), index = cases.findIndex((entry) => entry.index === state.caseIndex);
    const next = event.key === "Home" ? 0 : event.key === "End" ? cases.length - 1 : index + ({ ArrowUp: -1, ArrowDown: 1, PageUp: -50, PageDown: 50 })[event.key];
    const position = Math.max(0, Math.min(cases.length - 1, next));
    if (cases[position]) { state.caseIndex = cases[position].index; state.casePage = Math.floor(position / 50); renderCaseRows(); syncLocation(); focusCase(); }
  } else if (event.target.matches('[role="tab"]') && ["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) {
    event.preventDefault(); const tabs = ["cases", "scenarios", "raw"], index = tabs.indexOf(state.tab);
    state.tab = tabs[event.key === "Home" ? 0 : event.key === "End" ? 2 : (index + (event.key === "ArrowLeft" ? -1 : 1) + 3) % 3];
    render(); $(`tab-${state.tab}`).focus();
  }
});
window.addEventListener("hashchange", restoreLocation);
refresh().then(() => { routeReady = true; return restoreLocation(); });
