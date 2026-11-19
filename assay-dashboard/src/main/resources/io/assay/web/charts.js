/* Dependency-free chart helpers for the local Assay dashboard. */
(function (root) {
  "use strict";

  const COLORS = {
    good: "var(--chart-good)",
    warning: "var(--chart-warning)",
    bad: "var(--chart-bad)",
    neutral: "var(--silver)",
    info: "var(--teal)",
    muted: "var(--fog-light)"
  };

  const escape = (value) => String(value ?? "").replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[character]));

  const number = (value) => {
    if (value === null || value === undefined || value === "") return null;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  };

  const formatValue = (value, format) => {
    const parsed = number(value);
    if (parsed === null) return "—";
    if (format === "percent") return `${(parsed * 100).toFixed(1)}%`;
    if (typeof format === "function") return format(parsed);
    return parsed.toLocaleString("zh-CN", { maximumFractionDigits: 3 });
  };

  const empty = (message) => `<p class="chart-empty">${escape(message || "暂无数据")}</p>`;

  const rateColor = (value) => {
    const parsed = number(value);
    if (parsed === null) return COLORS.neutral;
    return parsed >= 0.9 ? COLORS.good : parsed >= 0.7 ? COLORS.warning : COLORS.bad;
  };

  function legendHtml(segments, format) {
    return `<div class="chart-legend">${segments.map((segment) => `<div class="chart-legend-row"><span class="chart-legend-label"><span class="chart-dot" style="background:${segment.color || COLORS.info}"></span>${escape(segment.label)}</span>${segment.value === undefined || segment.value === null ? "" : `<strong class="number">${escape(formatValue(segment.value, format || "number"))}</strong>`}</div>`).join("")}</div>`;
  }

  /** Horizontal bars with a label, track and value. Best for pass rates and counts. */
  function barChart(options = {}) {
    const items = (Array.isArray(options.items) ? options.items : [])
      .filter((item) => item && number(item.value) !== null);
    if (!items.length) return empty(options.empty);
    const max = Math.max(0.000001, number(options.max) ?? Math.max(...items.map((item) => Math.abs(number(item.value))), 1));
    const format = options.format || "number";
    const color = options.color || ((value) => options.tone === "rate" ? rateColor(value) : COLORS.info);
    return `<div class="bar-chart" role="img" aria-label="${escape(options.label || "条形图")}">${items.map((item) => {
      const value = number(item.value);
      const width = Math.max(0, Math.min(100, Math.abs(value) / max * 100));
      return `<div class="bar-row"><span class="bar-label" title="${escape(item.label)}">${escape(item.label)}</span><span class="bar-track"><span class="bar-fill" style="width:${width.toFixed(2)}%;background:${item.color || color(value)}"></span></span><span class="bar-value">${escape(formatValue(value, format))}</span></div>`;
    }).join("")}</div>`;
  }

  /** Two or more bars per row, sharing a common scale. Best for baseline vs candidate. */
  function groupedBar(options = {}) {
    const groups = (Array.isArray(options.groups) ? options.groups : [])
      .filter((group) => group && Array.isArray(group.values) && group.values.length);
    if (!groups.length) return empty(options.empty);
    const values = groups.flatMap((group) => group.values.map((value) => Math.abs(number(value.value) ?? 0)));
    const max = Math.max(0.000001, number(options.max) ?? Math.max(...values, 1));
    const format = options.format || "number";
    return `<div class="grouped-chart" role="img" aria-label="${escape(options.label || "分组条形图")}">${groups.map((group) => `<div class="grouped-row"><span class="bar-label" title="${escape(group.label)}">${escape(group.label)}</span><span class="grouped-bars">${group.values.map((value) => {
      const parsed = number(value.value);
      const width = Math.max(0, Math.min(100, Math.abs(parsed ?? 0) / max * 100));
      return `<span class="bar-track" title="${escape(value.label)}: ${escape(formatValue(parsed, format))}"><span class="bar-fill" style="width:${width.toFixed(2)}%;background:${value.color || COLORS.info}"></span></span>`;
    }).join("")}</span><span class="bar-value">${group.values.map((value) => `${escape(value.label)} ${escape(formatValue(value.value, format))}`).join(" · ")}</span></div>`).join("")}${Array.isArray(options.legend) && options.legend.length ? legendHtml(options.legend, format) : ""}</div>`;
  }

  /** Donut chart with a centered headline and a legend. */
  function donut(options = {}) {
    const segments = (Array.isArray(options.segments) ? options.segments : [])
      .filter((segment) => segment && (number(segment.value) || 0) > 0);
    const total = segments.reduce((sum, segment) => sum + (number(segment.value) || 0), 0);
    if (!total) return empty(options.empty);
    const size = number(options.size) || 160;
    const stroke = number(options.stroke) || 20;
    const radius = (size - stroke) / 2;
    const circumference = 2 * Math.PI * radius;
    let offset = 0;
    const circles = segments.map((segment) => {
      const value = number(segment.value) || 0;
      const length = value / total * circumference;
      const circle = `<circle cx="${size / 2}" cy="${size / 2}" r="${radius}" fill="none" stroke="${segment.color || COLORS.info}" stroke-width="${stroke}" stroke-dasharray="${length} ${circumference - length}" stroke-dashoffset="${-offset}" transform="rotate(-90 ${size / 2} ${size / 2})"><title>${escape(segment.label)}: ${escape(formatValue(value, options.format || "number"))}</title></circle>`;
      offset += length;
      return circle;
    }).join("");
    return `<div class="donut-wrap"><svg class="donut" viewBox="0 0 ${size} ${size}" role="img" aria-label="${escape(options.label || "环形图")}">${circles}<text x="${size / 2}" y="${size / 2 - 2}" text-anchor="middle" class="donut-value">${escape(options.centerValue ?? total)}</text><text x="${size / 2}" y="${size / 2 + 17}" text-anchor="middle" class="donut-label">${escape(options.centerLabel || "合计")}</text></svg><div class="chart-legend">${segments.map((segment) => `<div class="chart-legend-row"><span class="chart-legend-label"><span class="chart-dot" style="background:${segment.color || COLORS.info}"></span>${escape(segment.label)}</span><strong class="number">${escape(formatValue(segment.value, options.format || "number"))}</strong></div>`).join("")}</div></div>`;
  }

  /** Line chart with a light area fill, grid, dots and accessible tooltips. */
  function lineChart(options = {}) {
    const points = (Array.isArray(options.points) ? options.points : []).filter(Boolean);
    const valid = points.map((point, index) => ({ point, index, value: number(point.value) }))
      .filter((item) => item.value !== null);
    if (!valid.length) return empty(options.empty);
    const width = number(options.width) || 560;
    const height = number(options.height) || 190;
    const padX = 38;
    const padTop = 14;
    const padBottom = 28;
    const min = number(options.min) ?? 0;
    const max = number(options.max) ?? 1;
    const span = Math.max(0.000001, max - min);
    const x = (index) => padX + (points.length <= 1 ? (width - 2 * padX) / 2 : index * (width - 2 * padX) / (points.length - 1));
    const y = (value) => padTop + (1 - (value - min) / span) * (height - padTop - padBottom);
    const segments = [];
    let current = "";
    points.forEach((point, index) => {
      const value = number(point.value);
      if (value === null) {
        if (current) { segments.push(current); current = ""; }
        return;
      }
      current += `${current ? "L" : "M"}${x(index).toFixed(1)} ${y(value).toFixed(1)} `;
    });
    if (current) segments.push(current);
    const path = segments.join(" ").trim();
    const area = valid.length === points.length && points.length > 1
      ? `${path} L${x(points.length - 1).toFixed(1)} ${height - padBottom} L${x(0).toFixed(1)} ${height - padBottom} Z`
      : "";
    const format = options.format || "percent";
    const grid = [0, 0.5, 1].map((ratio) => {
      const value = min + span * ratio;
      return `<line x1="${padX}" y1="${y(value)}" x2="${width - padX}" y2="${y(value)}" class="trend-grid"/><text x="${padX - 6}" y="${y(value) + 3}" text-anchor="end" class="trend-label">${escape(formatValue(value, format))}</text>`;
    }).join("");
    const dots = valid.map((item) => `<circle cx="${x(item.index)}" cy="${y(item.value)}" r="3.5" class="trend-dot"><title>${escape(item.point.label)}: ${escape(formatValue(item.point.value, format))}</title></circle>`).join("");
    const labels = points.map((point, index) => `<text x="${x(index)}" y="${height - 6}" text-anchor="middle" class="trend-label">${escape(point.label)}</text>`).join("");
    return `<svg class="line-chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="${escape(options.label || "折线图")}">${grid}${area ? `<path d="${area}" class="trend-area"/>` : ""}<path d="${path}" class="trend-line"/>${dots}${labels}</svg>${options.summary || ""}`;
  }

  /** Single stacked bar with a legend. Best for state transitions. */
  function stackedBar(options = {}) {
    const segments = (Array.isArray(options.segments) ? options.segments : [])
      .filter((segment) => segment && (number(segment.value) || 0) >= 0);
    const total = segments.reduce((sum, segment) => sum + (number(segment.value) || 0), 0);
    if (!total) return empty(options.empty);
    const format = options.format || "number";
    return `<div class="stacked-chart" role="img" aria-label="${escape(options.label || "堆叠条形图")}"><div class="stacked-track">${segments.map((segment) => `<span class="stacked-fill" style="width:${((number(segment.value) || 0) / total * 100).toFixed(2)}%;background:${segment.color || COLORS.info}" title="${escape(segment.label)}: ${escape(formatValue(segment.value, format))}"></span>`).join("")}</div>${legendHtml(segments.map((segment) => ({ ...segment, color: segment.color || COLORS.info })), format)}</div>`;
  }

  const api = {
    COLORS,
    barChart,
    groupedBar,
    donut,
    lineChart,
    stackedBar,
    legend: legendHtml,
    formatValue,
    rateColor,
    escape
  };

  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.AssayCharts = api;
})(globalThis);
