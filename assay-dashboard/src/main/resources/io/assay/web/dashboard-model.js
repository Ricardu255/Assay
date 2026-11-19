/* Pure view-model helpers for the Assay dashboard. No DOM, no network. */
(function (root) {
  "use strict";

  const ui = (typeof module !== "undefined" && module.exports)
    ? require("./dashboard-core.js")
    : (root.AssayUI || {});

  const array = (value) => Array.isArray(value) ? value : [];
  const object = (value) => value && typeof value === "object" && !Array.isArray(value) ? value : {};
  const number = (value) => {
    if (value === null || value === undefined || value === "") return null;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  };
  const integer = (value) => number(value) ?? 0;
  const rate = (total, failures) => total > 0 ? Math.max(0, Math.min(1, (total - failures) / total)) : null;

  function caseStatus(result) {
    if (!result || result.hard_pass !== true) return "failed";
    return integer(result.soft_warning_count) > 0 ? "warning" : "passed";
  }

  function scenarioRows(cases) {
    const groups = new Map();
    for (const item of cases) {
      const name = item.scenario || "default";
      const row = groups.get(name) || { name, total: 0, passed: 0, warned: 0, failed: 0, soft: 0 };
      row.total++;
      row.soft += item.softWarnings;
      if (item.status === "failed") row.failed++;
      else {
        row.passed++;
        if (item.status === "warning") row.warned++;
      }
      groups.set(name, row);
    }
    return [...groups.values()].map((row) => ({
      ...row,
      rate: row.total ? row.passed / row.total : null
    })).sort((left, right) => (left.rate ?? 2) - (right.rate ?? 2)
      || right.total - left.total || left.name.localeCompare(right.name));
  }

  function failureReasons(failedCases) {
    const groups = new Map();
    for (const item of failedCases) {
      for (const check of item.failedChecks) {
        const key = `${check.level}|${check.name}`;
        const row = groups.get(key) || { name: check.name, level: check.level, count: 0 };
        row.count++;
        groups.set(key, row);
      }
    }
    return [...groups.values()].sort((left, right) => right.count - left.count
      || left.name.localeCompare(right.name));
  }

  function runVerdict(view) {
    const status = view.status === "budget_exhausted" ? "failed" : view.status;
    const title = status === "failed"
      ? "本次运行未通过"
      : status === "warning" ? "通过，但有复核信号" : "本次运行通过";
    const summary = status === "failed"
      ? `${integer(view.hardFailures)} 条用例硬失败，${integer(view.softWarnings)} 条软告警。`
      : status === "warning"
        ? `全部硬门禁通过，但有 ${integer(view.softWarnings)} 条软告警需要复核。`
        : "全部硬门禁通过，没有软告警。";
    const points = [];
    if (view.topFailures[0]) points.push(`首个失败用例：${view.topFailures[0].id}`);
    const reasons = view.failureReasons.slice(0, 3).map((item) => item.name);
    if (reasons.length) points.push(`主要失败规则：${reasons.join("、")}`);
    if (!points.length && view.caseCount) points.push(`共 ${integer(view.caseCount)} 条用例，全部通过硬门禁`);
    return {
      status,
      title,
      summary,
      points,
      nextAction: status === "failed" ? "查看用例与 Trace" : "查看规则统计"
    };
  }

  function runView(payload = {}) {
    const source = object(payload);
    const results = array(source.results);
    const cases = results.map((result, index) => {
      const item = object(result.case);
      const trace = object(result.trace);
      const checks = array(result.checks);
      const failedChecks = checks.filter((check) => check.passed !== true).map((check) => ({
        name: String(check.name || "未命名规则"),
        level: String(check.level || "hard"),
        message: String(check.message || "")
      }));
      return {
        index,
        id: String(item.case_id || item.id || `case-${index + 1}`),
        scenario: String(item.scenario || "default"),
        status: caseStatus(result),
        steps: array(trace.events).length,
        softWarnings: integer(result.soft_warning_count),
        failedChecks,
        result
      };
    });

    const failedCases = cases.filter((item) => item.status === "failed");
    const warnedCases = cases.filter((item) => item.status === "warning");
    const passedCases = cases.filter((item) => item.status === "passed");
    const caseCount = cases.length || integer(source.case_count);
    const hardFailures = cases.length ? failedCases.length : integer(source.hard_failures);
    const softWarnings = cases.length
      ? cases.reduce((sum, item) => sum + item.softWarnings, 0)
      : integer(source.soft_warnings);
    const passRate = rate(caseCount, hardFailures);
    const sourceStatus = String(source.status || "").toLowerCase();
    const status = hardFailures > 0 || sourceStatus === "failed" || sourceStatus === "budget_exhausted"
      ? "failed"
      : softWarnings > 0 ? "warning" : "passed";
    const view = {
      status,
      caseCount,
      hardFailures,
      softWarnings,
      passRate,
      cases,
      failedCases,
      warnedCases,
      passedCases,
      scenarios: scenarioRows(cases),
      rules: typeof ui.ruleStats === "function" ? ui.ruleStats(source) : [],
      metrics: typeof ui.metricStats === "function" ? ui.metricStats(source) : [],
      failureReasons: failureReasons(failedCases),
      topFailures: failedCases.slice(0, 5).map((item) => ({
        ...item,
        reasons: item.failedChecks.map((check) => check.name).slice(0, 3)
      })),
      payload: source
    };
    view.verdict = runVerdict(view);
    return view;
  }

  function overviewView(items) {
    const list = array(items);
    const runs = list.filter((item) => item && item.kind === "run");
    const totalCases = runs.reduce((sum, item) => sum + integer(item.case_count), 0);
    const hardFailures = runs.reduce((sum, item) => {
      const cases = integer(item.case_count);
      return sum + Math.min(cases, integer(item.hard_failures));
    }, 0);
    const softWarnings = runs.reduce((sum, item) => sum + integer(item.soft_warnings), 0);
    const runStatus = { passed: 0, warning: 0, failed: 0 };
    for (const item of runs) {
      if (String(item.status) === "failed" || String(item.status) === "budget_exhausted") runStatus.failed++;
      else if (integer(item.soft_warnings) > 0) runStatus.warning++;
      else runStatus.passed++;
    }
    return {
      runs: runs.length,
      totalCases,
      hardFailures,
      softWarnings,
      passRate: rate(totalCases, hardFailures),
      runStatus,
      trend: typeof ui.runTrend === "function" ? ui.runTrend(list, 7) : { days: 7, buckets: [], totals: {} },
      health: typeof ui.suiteHealth === "function" ? ui.suiteHealth(list, 8) : []
    };
  }

  function compareView(payload = {}) {
    const source = object(payload);
    const gate = object(source.gate);
    const baseline = object(source.baseline);
    const candidate = object(source.candidate);
    const deltas = object(source.deltas);
    const transitions = object(source.transitions);
    const regressions = array(source.regressions).length
      ? array(source.regressions)
      : array(transitions.regressed);
    const status = String(gate.status || "unknown").toLowerCase();
    const scenarios = Object.entries(object(source.scenarios)).map(([name, raw]) => {
      const row = object(raw);
      return {
        name,
        caseCount: integer(row.case_count),
        baseline: number(row.baseline_hard_pass_rate),
        candidate: number(row.candidate_hard_pass_rate),
        delta: number(row.hard_pass_rate_delta),
        regressions: integer(row.regressions),
        critical: integer(row.critical_regressions)
      };
    }).sort((left, right) => (left.delta ?? 0) - (right.delta ?? 0) || left.name.localeCompare(right.name));
    const rules = Object.entries(object(source.rules)).map(([name, raw]) => {
      const row = object(raw);
      return {
        name,
        baseline: number(object(row.baseline).pass_rate),
        candidate: number(object(row.candidate).pass_rate),
        delta: number(row.pass_rate_delta)
      };
    }).sort((left, right) => (left.delta ?? 0) - (right.delta ?? 0) || left.name.localeCompare(right.name));
    const transitionSegments = [
      { label: "回归", value: integer(transitions.regressed_count), color: "var(--chart-bad)" },
      { label: "恢复", value: integer(transitions.recovered_count), color: "var(--chart-good)" },
      { label: "持续失败", value: integer(transitions.persistent_failure_count), color: "var(--chart-warning)" },
      { label: "稳定通过", value: integer(transitions.stable_pass_count), color: "var(--silver)" }
    ].filter((segment) => segment.value > 0);
    return {
      status,
      gate,
      baseline,
      candidate,
      deltas,
      transitions,
      regressions,
      scenarios,
      rules,
      transitionSegments,
      kpis: {
        baselinePassRate: number(baseline.hard_pass_rate),
        candidatePassRate: number(candidate.hard_pass_rate),
        passRateDelta: number(deltas.hard_pass_rate),
        regressed: integer(transitions.regressed_count),
        criticalRegressions: regressions.filter((row) => object(row).critical).length,
        caseCount: integer(candidate.case_count)
      },
      payload: source
    };
  }

  function autoView(payload = {}) {
    const source = object(payload);
    const rounds = array(source.rounds);
    const decisions = { accept: 0, reject: 0, rollback: 0 };
    const roundDecisions = rounds.map((rawRound, index) => {
      const round = object(rawRound);
      const counts = { accept: 0, reject: 0, rollback: 0 };
      for (const entry of array(round.candidates)) {
        const decision = String(object(object(entry).evaluation).decision || "").toLowerCase();
        if (decision in counts) {
          counts[decision]++;
          decisions[decision]++;
        }
      }
      return { label: `第 ${round.round || index + 1} 轮`, counts };
    });
    const roundPassRates = rounds.map((rawRound, index) => {
      const round = object(rawRound);
      let baseline = null;
      let candidate = null;
      for (const entry of array(round.candidates)) {
        const comparison = object(object(object(object(entry).evaluation).comparisons).improvement);
        const rate = number(object(comparison.candidate).hard_pass_rate);
        if (rate !== null && (candidate === null || rate > candidate)) {
          candidate = rate;
          baseline = number(object(comparison.baseline).hard_pass_rate);
        }
      }
      return { label: `第 ${round.round || index + 1} 轮`, baseline, candidate };
    });
    return {
      rounds: rounds.length,
      candidates: decisions.accept + decisions.reject + decisions.rollback,
      decisions,
      roundDecisions,
      roundPassRates,
      usage: object(source.usage),
      status: String(source.status || "unknown").toLowerCase(),
      currentVersion: source.current_version,
      error: source.error
    };
  }

  function releaseView(payload = {}) {
    const source = object(payload);
    const stages = array(source.stages).map((rawStage, index) => {
      const stage = object(rawStage);
      const caseCount = integer(stage.case_count);
      const hardFailures = Math.min(caseCount, integer(stage.hard_failures));
      return {
        index,
        suite: String(stage.suite || `阶段 ${index + 1}`),
        status: String(stage.status || "unknown"),
        caseCount,
        hardFailures,
        passed: Math.max(0, caseCount - hardFailures),
        passRate: caseCount > 0
          ? Math.max(0, Math.min(1, (caseCount - hardFailures) / caseCount))
          : null
      };
    });
    return {
      releaseId: source.release_id,
      status: String(source.status || "unknown"),
      stages
    };
  }

  const api = { caseStatus, runView, overviewView, compareView, autoView, releaseView };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.AssayModel = api;
})(globalThis);
