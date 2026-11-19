# Assay 吸收 deepeval 设计方案（2026-11-18）

> 目的：把 `deepeval-main/`（deepeval 4.3.4 快照）里值得吸收的设计，映射成 Assay 可落地的模块、接口和路线图；同时明确哪些东西不要抄。
>
> 参考快照：`deepeval-main/`（未纳入版本控制的参考代码，行号以该快照为准；上游演进后行号会漂移）。
>
> 结论摘要：**deepeval 值得吸收的是"指标结果契约 + QAG 判定循环 + 轨迹驱动的 agentic 指标 + 缓存/合成/prompt 优化闭环"；它的预算控制、存储和恢复弱于 Assay，不应反向学习；SaaS、Python 运行时和重依赖生态明确放弃。**
>
> 原则：借语义，不借运行时；核心零依赖不破；先确定性、后模型；抄之前先修掉 deepeval 自身已知的缺陷。

## 实施状态（2026-11-18）

| 能力 | 状态 | 落点 |
|---|---|---|
| Metric 结果契约（score/threshold/reason/breakdown/skip/error） | ✅ 已完成 | `io.assay.model.MetricResult`、`io.assay.metrics.Metric` |
| Tool Permission 确定性指标 | ✅ 已完成 | `ToolPermissionMetric` |
| Loop Detection 确定性指标（重复工具/停滞/循环序列） | ✅ 已完成 | `LoopDetectionMetric` |
| G-Eval-lite + LLM judge | ✅ 已完成 | `GevalMetric`、`LlmJudge`、`OpenAiCompatibleJudge` |
| Trace 渲染与默认脱敏 | ✅ 已完成 | `TraceRenderer` |
| 指标配置（preset / JSON） | ✅ 已完成 | `MetricPresets`、`MetricConfig`、CLI `--preset`/`--metrics` |
| Metric 级内容寻址缓存 | ✅ 已完成 | `MetricCacheKey`、`FileMetricCache`、CLI `--metric-cache` |
| 指标聚合与报告表 | ✅ 已完成 | `MetricSummary`、`report.md`「Metric scores」 |
| judge token 计入预算（含 resume 回填） | ✅ 已完成 | `BudgetLedger.chargeMetrics`、`EvaluationEngine` |
| Dashboard 指标历史/趋势 | ✅ 已完成 | `/api/metric-history` +「指标」tab |
| 合成用例（文档/轨迹 → JSONL，质量门 + 去重 + provenance） | ✅ 已完成（v1） | `assay-synth`、CLI `synth` |
| 对抗用例生成（9 类攻击、严重级别、安全行为） | ✅ 已完成（v1） | `AdversarialGenerator`、CLI `attack` |
| 演化目标引用指标分（`metric:<name>`）与 `metric_deltas` 报告 | ✅ 已完成 | `MetricStats`/`EvolutionEngine` |
| 合成/对抗数据边界（improvement-only、比例上限、provenance） | ✅ 已完成 | `SyntheticPolicy`、`EvolutionPolicy` |
| HTTP Server 提交路径接入指标配置 | ✅ 已完成 | `RunRequest`/`RunSpec`/`SuiteRunner` |
| 超时预算推导（含 `timeout_budget_seconds`） | ✅ 已完成 | `EvaluationSettings`/`RetryPolicy` |
| 假 HTTP 服务测试架（judge + synth + attack） | ✅ 已完成 | `HttpJudgeTest`、CLI `--base-url/--model` |
| GEPA / COPRO prompt 优化（v1：候选生成 + 去重） | ✅ 已完成 | `PromptOptimizer`、`AutoEvolutionAdapter` |
| 富控制台报告（`--display`、指标理由、失败检查） | ✅ 已完成 | `ConsoleReport` |
| 落盘/评审前凭据脱敏 | ✅ 已完成 | `TraceRedactor`、`EvaluationEngine` |
| 常见 PII 掩码（邮箱/手机号/身份证） | ✅ 已完成 | `TraceRedactor` |
| Server HTTP 层指标配置测试 | ✅ 已完成 | `RunApiTest` |
| run metadata 数据集哈希 | ✅ 已完成 | `EvaluationEngine.datasetHash` |
| 评估器校准报告（混淆矩阵 / P / R / F1 / agreement） | ✅ 已完成 | `CalibrationReport`、CLI `calibrate` |
| 对话模拟器 | ⬜ 待做 | 会话级 trace 后接入 |
| Benchmark 适配器 | ⬜ 待做 | 本地 JSONL |

---

## 一、定位差异

| 维度 | Assay | deepeval 4.3.4 |
|---|---|---|
| 形态 | Java 17 多模块框架，面向外部 Agent 的评测流水线 | Python 库 + pytest 插件 + Confident AI SaaS |
| 评测核心 | 确定性规则 + 可选 LLM 复核（不参与判定） | 50+ 模型评分指标，`score/threshold` 直接决定通过 |
| 样例单元 | `EvalCase(payload, expected, scenario, suite)`，输入来自任意 Adapter | `LLMTestCase` / `ConversationalTestCase`，内建 retrieval/tools/turns |
| 结果单元 | `CheckResult(passed, actual, expected, message)`，boolean | `BaseMetric` 实例状态（score/threshold/reason/breakdown/cost/tokens/skipped/flaky） |
| 强项 | Trace 归一化、四层评测、预算账本、熔断、真 resume、演化策略、队列/Dashboard | 指标契约统一、QAG 判定、agentic/RAG/对话指标族、合成数据、prompt 优化 |
| 弱项 | 没有分数型指标抽象、没有合成/红队、没有 prompt 优化 | 预算控制弱、无本地 run 对比、缓存键有缺陷、SaaS 强耦合 |

这决定了吸收策略：**deepeval 是"评测语义"的最好教材，但不是"评测运行时"的教材。**

---

## 二、吸收原则

1. **借语义，不借运行时。** 学 `MetricResult` 的字段设计、QAG 的判定循环、G-Eval 的两阶段评分；不学 asyncio/Semaphore/ContextVar、pydantic 序列化、模块级单例。
2. **核心零依赖不破。** `assay-core` 仍不引入 Jackson、Spring、OTel；指标层只用现有 `Json`/`Jsonable` 和 `LlmReviewer` 接口。
3. **先确定性、后模型。** 优先吸收零 LLM 成本的指标（Tool Permission、Tool Correctness、Loop Detection），再吸收 G-Eval-lite；模型指标永远可选。
4. **抄之前先修缺陷。** deepeval 有四个已被证实的坑（见第九节）：skip 静默消失、缓存键不含 trace/tools、空集默认满分、`score_breakdown` 不持久化。Assay 要反着做。
5. **不改变现有契约。** 新指标以附加字段进入 `CaseResult`，再映射为 `CheckResult` 参与现有门禁；`results.json` / `report.md` 的既有字段保持兼容。

---

## 三、指标层（最高价值）

### 3.1 统一结果契约（P0，最该抄）

deepeval 的 `BaseMetric` 把每个指标的结果收敛成同一组字段（`deepeval-main/deepeval/metrics/base_metric.py:111-182`）：

```
score, threshold, success, reason, score_breakdown,
error, skipped, flaky, strict_mode, evaluation_model,
evaluation_cost, input_tokens, output_tokens, verbose_logs
```

对照 Assay：`CheckResult` 只有 `layer/level/name/passed/actual/expected/message/suspected_modules`——**没有分数、没有阈值、没有理由、没有成本、没有跳过态**。所有语义判断只能退化成 boolean，`llm_review` 又是自由 `Map`。

#### 设计

新增 `assay-core/src/main/java/io/assay/metrics/`：

```java
public interface Metric {
    String name();
    MetricResult measure(MetricContext ctx);   // ctx = EvalCase + NormalizedTrace + LlmJudge

    /** 配置指纹，参与缓存键。 */
    default String fingerprint() { return name(); }
}

public record MetricResult(
        String name,
        double score,                  // [0,1]
        double threshold,
        boolean success,
        String reason,
        Map<String, Object> breakdown,
        List<Map<String, Object>> evidence,
        boolean skipped,
        boolean flaky,
        String error,
        long inputTokens,
        long outputTokens,
        java.math.BigDecimal costUsd,
        String model) {}
```

`MetricContext` 至少包含：`EvalCase`、`NormalizedTrace`、`List<NormalizedTrace>`（一致性场景）、可选 `LlmJudge`、`TokenBudget`。`LlmJudge` 从现有 `LlmReviewer` 背后抽出，提供"带 schema 的 JSON 调用 + usage 回传"，不改变 `LlmReviewer` 的语义。

#### 与现有门禁的接法

`CaseResult` 增加 `List<MetricResult> metrics`。落库/报告前把每个指标按用例或 adapter 配置映射成 `CheckResult`：

| MetricResult | CheckResult |
|---|---|
| `success == true` | `passed=true` |
| `success == false` | `passed=false` |
| `skipped == true` | 不生成 CheckResult，单独进 `metrics`，不计入通过率 |
| `error != null` | `passed=false`，`layer="metric"`，默认 hard |

这样 `EvaluationEngine.hardPass()`、`QualityGate`、`EvolutionEngine` 的指标对比**完全不用改**，只是多了一个数据来源。

#### 验收

- `Metric`/`MetricResult` 单测覆盖 score 边界、threshold 相等、skip、error。
- `results.json` 增加 `metrics` 数组；旧字段不变。
- `report.md` 增加"指标"段落：name、score、threshold、reason（失败项优先）。

---

### 3.2 QAG 判定循环（P0）

deepeval 的通用套路（`deepeval-main/deepeval/metrics/utils/qag.py:113-138`）：LLM 只输出 `{verdicts:[{verdict,reason}]}`，代码负责清洗与计分。

- `Verdict{YES, NO, BORDERLINE}`，`YesNo`/`YesNoBorderline` 两套词表；
- 清洗：小写、取首个单词、兼容历史 `idk`、丢弃词表外项（`qag.py:58-110`）；
- 分数 = 通过项比例，**空集策略由指标显式声明**（不要沿用 deepeval 默认 `empty_score=1`）；
- `reason` 只汇总失败项，且只在 `includeReason=true` 时额外调用一次 LLM。

实现为 `io.assay.metrics.qag.QagLoop`：输入"单元列表 + 判定 prompt + 通过词表"，输出 `QagResult(passedCount, total, verdicts, score)`。首个落地指标：**Argument Correctness**——对每个 tool call 判"给定输入参数是否合理"；Assay 的 `TraceEvent.fields` 已能提供工具名和参数。

#### 验收

- 判定清洗有单测（大写、`idk`、词表外、空数组）。
- 空集场景必须在指标声明处显式选择 `pass` 或 `skip`，并有测试固定该语义。

---

### 3.3 确定性 agent 指标（P0，零 LLM 成本，最契合 Assay）

这几项 deepeval 用纯代码实现，正好延续 Assay"确定性优先"的定位：

| 指标 | deepeval 实现 | Assay 落地 |
|---|---|---|
| **Agent Loop Detection** | 三信号加权：工具重复（同 `(name, sorted args)` ≥3 次 0.5，≥6 次 0）、相邻 LLM 输出 bigram Jaccard/SequenceMatcher ≥0.85 判停滞、调用图 DFS 祖先路径重复判环；权重 0.40/0.35/0.25，写入 breakdown（`metrics/agent_loop_detection/agent_loop_detection.py:294-549`） | 低。`MultiAgentMetrics` 已有 `maxRepeatedStep`，补齐"相邻输出相似度"和"调用图环检测"即可；`breakdown` 直接展示三信号 |
| **Tool Permission** | 纯 allow/deny 名单，分数 = 授权调用占比（`tool_permission.py:79-127`） | 很低。`NormalizedTrace` 已有 `deniedTools`；改成"策略配置 + 明细" |
| **Tool Correctness** | exact / ordering（加权 LCS）/ partial（按 expected tools 找最佳 called tool，参数按 key 重叠给分）（`tool_correctness.py:518-603`）；reason 由代码拼接 | 中。`Rules` 已有 `sequence_eq/sequence_contains`，扩展为"带参数顺序的部分匹配" |
| **Step Efficiency** | 需 trace；先抽 task 再让 LLM 打 0-1 分（`step_efficiency.py:107-227`） | 中，归入模型指标 |

前三个建议直接并入 `assay-core/metrics`，不新增依赖。`MultiAgentMetrics` 保持纯记录器定位，新指标读取它 + `NormalizedTrace` 计算分数。

#### 验收

- Loop Detection 的三个信号分别有构造用例：重复工具、停滞输出、递归调用图。
- Tool Correctness 覆盖 exact/order/partial 三种模式，参数部分匹配给分比例有测试。
- 所有确定性指标在 `--no-llm`（默认）下可运行。

---

### 3.4 G-Eval-lite（P0/P1）

deepeval 的两阶段评分（`metrics/g_eval/g_eval.py:320-342`）：

1. 只给 `criteria` 时，先让 LLM 生成 `evaluation_steps`（可缓存、可人工编辑）；
2. 再按 steps 输出 `{reason, score(0-10)}`；
3. `score/10` 归一化；`strict_mode` 改为 0/1 prompt 且 `threshold=1`；
4. rubric 支持 `[{score_range, expected_outcome}]` 自定义分档（`metrics/g_eval/utils.py:35-50`）；
5. `include_reason=false` 时省掉额外调用。

Assay 现有 `OpenAiCompatibleReviewer` 是一个大 prompt + 固定 keys，不产出分数（`assay-runtime/src/main/java/io/assay/llm/OpenAiCompatibleReviewer.java`；调用点 `assay-core/src/main/java/io/assay/engine/EvaluationEngine.java:377-410`）。改造路径：

- `LlmReviewer` 保留为"诊断/建议"通道；
- 新增 `GevalMetric(criteria, steps, threshold, scoreRange)`，实现 `Metric`；
- prompt 模板放 classpath 资源，带版本号，参与缓存键；
- 模型返回非法 JSON 时写 `MetricResult.error`，不丢 case。

**不做**：logprob 概率加权（deepeval `g_eval/utils.py:346-413`）——OpenAI-compatible 端点支持不齐，收益低。

#### 验收

- 给定 criteria 能生成 steps；给定 steps 不再调用生成；
- 0-10 归一化、strict 0/1、阈值相等边界有测试；
- 非法 JSON、超时、HTTP 4xx 分别映射为 error，且不改变其他指标的通过率。

---

### 3.5 轨迹型 agent 指标（P1）

Task Completion / Plan Adherence / Plan Quality 的共同套路：**先从 trace 抽取 task/plan/outcome，再对抽取结果打分**（`task_completion.py:172-232`、`plan_adherence.py:115-279`）。

Assay 的 `NormalizedTrace.events + finalOutput` 够用，缺的是稳定的 **trace→prompt 渲染器**（P0 基建）：

- `TraceRenderer.full(trace)`：`module/action/status/fields/finalOutput`，供语义指标；
- `TraceRenderer.compact(trace)`：裁剪噪声、限制条目；
- `TraceRenderer.timed(trace)`：保留 `durationMs`，供效率指标；
- **默认脱敏**：`Authorization`、`api_key`、`Bearer`、邮箱、手机号；deepeval 默认不脱敏，这一点反着做。

#### 空集语义（必须显式）

deepeval 的 Plan Adherence 在 trace 抽不到 plan 时**直接给 1.0**（`plan_adherence.py:247-279`），Argument Correctness 无工具调用给 1.0。这会让"空 trace"绕过质量门。Assay 的规则：

- `PlanAdherence` 抽不到 plan → `skipped=true, reason="no plan found"`；
- `ArgumentCorrectness` 无 tool call → 由用例配置决定 `skipped` 或 `score=1`，默认 `skipped`；
- 报告里 skip 单列，不进入通过率分母。

---

### 3.6 指标层明确不抄

- **System One / Jev 双决策引擎**（`eval_mode=llm|hybrid|system_one`、confidence、fallback reason）：供应商耦合 + 过度设计。
- **DAG metric**（`metrics/dag/runner.py:43-133`）：思想是把"先判 A 再走 B"显式化，但实现靠 `inspect` 反射复制指标、多根节点异步时 `metric.score` 最后写入者获胜、无聚合语义。若未来要做，简化为 JSON 配置的判定树，结果显式返回并聚合。
- **RAG 四件套 / `ragas.py` / BLEU-ROUGE-BERTScore**：torch、langchain、datasets 等重依赖，且 Assay 的 trace 不以 retrieval 为中心。仅在接入 RAG Agent 后再评估。
- **语音/多模态/Arena 指标**：超出 `NormalizedTrace` 范围。
- **每个指标写同步/异步两套完整流程**（300-600 行/指标）：Java 用模板方法 `final measure() → doMeasure()`，异步用 `CompletableFuture`。
- **运行时动态 schema**（DAG NonBinary 节点）：改为预定义 JSON Schema + 手工校验。

---

## 四、执行层（Assay 已更强，只补三处）

先明确不学的部分：**预算账本（预扣/结算/overrun）、熔断、真 resume、事务化存储，Assay 都强于 deepeval**。deepeval 只做事后汇总，未知成本传播为 `None`，没有硬上限，也没有正式 resume（temp JSON 只是崩溃残留）。

### 4.1 Metric 级缓存（P1，价值最高）

deepeval 的双层 key（`deepeval/test_run/cache.py:141-184`）：

- case 级：input / actual_output / expected_output / context / retrieval_context / hyperparameters，排序后序列化；
- metric 级：指标名 + 配置逐字段（threshold/model/strict/criteria/rubric/schema）+ token 序列。

**缺陷：键里没有 `tools_called`、`expected_tools`、`_trace_dict`（`cache.py:148-175`）——对 agent/trace 指标会错误复用。** Assay 的键必须包含：

```
SHA-256(
  trace 内容哈希（events + finalOutput + fields，稳定序列化）
  + case payload 哈希
  + metric.name() + metric.fingerprint()
  + prompt 模板版本 + 模型 id + temperature
)
```

实现建议：

- `MetricCacheKey`、`CachedMetricResult`、`MetricResultCache`（内存 LRU + 可选文件落盘）；
- 命中时回填 `score/reason/success/breakdown`，`costUsd=0`，并标记 `cached=true`；
- CLI：`--metric-cache`、`--write-metric-cache`、`--cache-dir`；
- `report.md` 标注 `cached`。

### 4.2 三态语义与部分结果（P1）

deepeval 有 `success=PASS/FAIL/None`（无阈值不算 pass 也不算 fail，`test_run.py:672-682`），超时时保留已完成指标。**但它让 skipped 从结果里静默消失**（`execute/e2e.py:437-443`）。

Assay 的语义：

| 状态 | 含义 | 计入通过率 | 门禁 |
|---|---|---|---|
| `PASS` | 指标成功 | 是 | — |
| `FAIL` | 指标失败 | 是 | 按 hard/soft |
| `SKIP` | 缺前置条件/预算跳过 | 否 | 不计入，但报告可见 |
| `ERROR` | 指标自身执行失败 | 否（单独统计） | 默认 hard fail |
| `NOT_EVALUATED` | 用例超时前未执行 | 否 | 报告可见 |

实现要求：每 case 独立 try/finally；已完成指标永远落库；超时把当前/后续指标标 `NOT_EVALUATED`；`SKIP` 不静默丢弃。

### 4.3 成本归因与"未知 ≠ 0"（P1）

deepeval 的 `_accrue_cost()` 语义是"任一子调用成本未知则整体置 `None`"（`base_metric.py:188-203`）。Assay 的 README 已写明 `--use-llm` 的复核调用尚未计入预算——这是应当补的洞：

- `MetricResult` 的 `inputTokens/outputTokens/costUsd` 用可空类型，累加规则 `unknown + known = unknown`；
- 评审模型调用接入 `BudgetLedger`：先按 prompt 估算预扣，拿到 usage 后结算（复用 `CostEstimator`，新增 judge 估算方法）；
- 报告增加"每 metric / 每 case 成本"列；
- 缓存命中记 0，并标记 `cached=true`，使"免费"与"未知"可区分。

### 4.4 超时预算自洽（P2，低成本）

deepeval 由重试配置反推外层超时：`outer ≈ attempts × perAttempt + expectedBackoff + 1s`，预留 buffer = clamp(15% outer, 10-60s)（`config/settings.py:1094-1174`）。

Assay 的 `EvaluationSettings` 目前 timeout/retries 各自独立。建议加 `deriveTimeout()` 并在配置校验阶段告警：`timeoutSeconds < retries × perAttemptTimeout` 时提示"重试尚未跑完外层已超时"。

### 4.5 富控制台报告（P2）

按指标聚合：avg score、pass rate、skip/error/flaky 计数、总成本；per-case 树；`--display all|passing|failing`。

Assay 已有 `report.md` 与 `CompareReport`，增量集中在：

- 失败指标的 `reason` 与 `breakdown`（尤其 Loop Detection 的三信号）；
- 指标级趋势（可复用 Dashboard 的 rule-history 模式，新增 `/api/metric-history`）。

---

## 五、数据生成与优化闭环（真正补短板）

### 5.1 从文档/trace 合成用例（P0/P1，性价比最高）

deepeval 的合成链（`deepeval/synthesizer/synthesizer.py:966-1149`）：

1. 依据 context 批量生成 input；
2. 质量评分，低分带 feedback 重写；
3. 每个 input 做 N 次随机加权进化（7 种：Multi-context / Reasoning / Concretizing / Constrained / Comparative / Hypothetical / In-Breadth）；
4. 套 styling（scenario/task/input_format/expected_output_format）；
5. 生成 expected_output，写 `evolutions`、`quality`、`sourceFiles` 元数据。

值得抄的：

- 四个独立配置对象：`FiltrationConfig`、`EvolutionConfig`、`StylingConfig`、`ContextConstructionConfig`；
- 过滤 prompt 只评两点：Self-Containment + Clear Objective，输出 `{score, feedback}`（`templates/template.py:528-577`）；
- 7 种进化 prompt 的共同约束（必须可从 context 完全回答、不得出现"根据上下文"、尽量 ≤15 词）——约束本身比算法更有价值；
- 生成输出走"原生 schema 约束 → JSON 解析 → 文本降级"三级回退（`synthesizer.py:1682-1732`）。

**必须修掉的两个缺陷**（否则不要做）：

1. **没有 case 级去重** → Assay 加 MinHash/embedding 相似度 >0.9 去重；
2. **质量重写次数耗尽后仍保留低分 case**（`synthesizer.py:1516-1551`）→ Assay 改为"丢弃或隔离 + 记录原因"。

#### 设计：`assay-synth` 新模块

```
assay-synth/
  src/main/java/io/assay/synth/
    SynthesisConfig.java        // scenario/task/inputFormat/expectedOutputFormat
    FiltrationConfig.java       // qualityThreshold, maxRewriteRetries
    ContextChunker.java         // 自研 token 近似切块，不引入 LangChain/Chroma
    ContextSampler.java         // 每文档 N 个 context、每 context M 个 chunk
    CaseEvolver.java            // 七种策略，显式 seed
    CaseRewriter.java           // 带 feedback 重写
    CaseDeduplicator.java       // MinHash / embedding 二选一
```

- 输出沿用 Assay case JSONL，新增 `metadata.source=synthetic|mined`、`metadata.evolutions[]`、`metadata.quality`、`metadata.sourceFiles[]`、`metadata.originCaseId`；
- CLI：`assay synth --from docs|traces --out improvement.jsonl --per-context 2 --evolutions 1 --budget-usd 5`；
- **只允许进 improvement set**；regression/holdout 保持不可污染；
- `EvolutionPolicy` 新增 `syntheticRatioCap`（默认 ≤30%）与 `requireProvenance=true`；accept/reject 报告按 synthetic/real 分层给出改进量，防止对合成分布过拟合。

### 5.2 对抗用例生成（P1，注意事实）

**deepeval 的 `red_teaming/` 在 v3.0 后已整体迁出**，目录里只剩 README 指向 DeepTeam，没有可复用的实现。只能借鉴分类法：

- prompt injection、jailbreak、role-play、编码混淆、PII 提取、工具滥用、数据外泄、 misinformation、过度拒答。

Assay 做"红队 lite"：

- `AttackCategory` 枚举 + `AttackStrategy` 接口（单轮/多轮升级/变异）；
- 固定种子 + 模板库 + 变异（同义改写/编码/语言切换）；
- 输出同样 JSONL，增加 `attackCategory`、`severity`、`expectedSafeBehavior`；
- `SafetyGate`：确定性检测（正则 PII/密钥、工具白名单、URL 外联）+ 可选 LLM judge；
- `EvolutionPolicy` 加 `safetyFloor`：候选在安全维度低于基线即 reject/rollback。

关键词：**把攻击 case 当作另一维评测输入，复用现有 baseline/candidate 执行与 gate，不另造评估器。**

### 5.3 GEPA/COPRO 式 prompt 优化（P2，接 evolve-auto）

Assay 的 `evolve-auto` 是"确定性生成候选"；deepeval 补的是"反馈驱动的候选生成"。最值得翻译的是 GEPA 主循环（`deepeval/optimizer/algorithms/gepa/gepa.py:166-282`）：

- 候选树 `PromptConfiguration(id, parent, prompts)`；
- Pareto 存档：**非支配才接受**，接受后删除被其支配的旧候选；
- 按 Pareto 频率加权选父代（`policies.py:62-169`）；
- minibatch 廉价筛选 + 周期性全量验证；
- `patience=3` 提前停；
- 平票用 `PREFER_ROOT / PREFER_CHILD / RANDOM`（`policies.py:172-229`）。

映射到 Assay：

| deepeval | Assay |
|---|---|
| iterations | `EvolutionPolicy` 新增/复用 `maxRounds` |
| LLM 调用预算 | `EvolutionBudget` |
| Pareto 存档 | 新增 `ParetoArchive` |
| minbatch | `EvolutionPolicy` 的筛选集 |
| `minImprovement` | 只用于"晋升新 baseline"，不用于 Pareto 存档 |
| 随机源 | 必须显式 `seed`，守住确定性原则 |

实现 GEPA + COPRO 两种即可；SIMBA（高低方差 trace 对）成本高，MIPROV2（Optuna TPE）与 Assay 定位冲突，不做。

### 5.4 对话模拟器与 benchmark（P3，按需）

- **模拟器**：等 Assay 支持会话级 trace 后再做。先做线性多轮 + `Persona` + `maxUserTurns`/`ExpectedOutcomeStop`/`NoProgressStop`；`ScriptedUser`（零 LLM）优先，`LlmUser` 可选。
- **Benchmark**：只支持本地 JSONL，抽象 `BenchmarkAdapter`（`loadCases/renderPrompt/normalizeAnswer/score`），先做 MMLU（四选一）、GSM8K（数值）、BoolQ（是否）三类；**不要**照搬 HuggingFace 在线下载。用途是给 adapter 替换提供 holdout 基线，不替代 agent 评测。

---

## 六、集成与交付

### 6.1 追踪：只做 OTel 之上的一层薄语义（P2）

deepeval 是"自研 ContextVar span + OTel bridge"双系统，为此付出 `_PendingSlot`、异步生成器补丁、task bindings 等复杂度（`tracing/tracing.py:88,843-1105`；`tracing/context.py:356,409`）。**Java 侧不要复刻**：

- 以 OTel 为唯一事实源，另建语义层：`SpanType{AGENT, LLM, TOOL, RETRIEVER, HANDOFF}` + `caseRef`；
- `try (AssaySpan s = ...)` + 显式 `TraceContext.wrap()` 跨线程池传播，不依赖 `InheritableThreadLocal`；
- 抄它的 `next_span()` 预置字段机制（框架在别处起 span、业务代码后补 testCase/expected/tools）；
- 抄适配器分层：`OtelSpanMapper`（纯函数：`matches/type/fields`）+ 框架胶水。

### 6.2 集成适配器 SPI（P2）

从现有 AgentScope 适配器抽出两个接口，避免每接一个框架复制粘贴：

```java
public interface FrameworkAdapter {      // ServiceLoader 发现
    String name();
    boolean isAvailable();
    void install(SpanSink sink);
    void uninstall();
}

public interface OtelSpanMapper {        // 纯函数，无猴子补丁
    boolean matches(SpanData span);
    SpanType type(SpanData span);
    Map<String, Object> fields(SpanData span);
}
```

首批做 LangChain4j、Spring AI；优先官方 listener/interceptor 与 `gen_ai.*` 语义约定，不反射改私有方法。`Integration` 用枚举式规范名（`deepeval/tracing/integrations.py:6`），避免 `openai/OpenAI/OpenAi` 分叉。

**反例**：deepeval 的 `deepeval/openai/__init__.py`、`anthropic/__init__.py` 一 import 就启动追踪和遥测；`patchers.py` 直接 `setattr` 客户端实例。Java 侧改为显式 `AssayTracing.install(...)` 或 Spring Boot 条件装配。

### 6.3 Skills 打包（P1，一两天工作量）

deepeval 的 `.claude-plugin/plugin.json` 只声明 `"skills": "./skills/"`，一套 skills 同时服务 Claude Code 和 Cursor；每个 `SKILL.md` 是"路由说明 + 编号工作流 + references + templates"，并用 `DO NOT TRIGGER` 划清边界。

Assay 照做：

```
skills/assay/SKILL.md
skills/assay/references/{evaluation-workflow,tracing,ci-gating,metrics}.md
skills/assay/templates/{EvalTest.java,AssayMetrics.java}
.claude-plugin/plugin.json      # {"skills": "./skills/"}
.cursor-plugin/plugin.json
```

`SKILL.md` 必须写清：何时用 Assay、`assay run` 而不是裸 JUnit、失败后按 trace 下钻、密钥只按环境变量名引用、禁止打印 `.env`。

### 6.4 配置溯源与脱敏（P2）

deepeval 的 `diagnose` 能回答"这个值来自哪"（`deepeval/cli/diagnose/diagnose.py:151,482`）：`{key, maskedValue, source}`，source 区分 `env:OPENAI_API_KEY` / `file:.assay.env:12` / `default`；dotenv 优先级 `.env → .env.<APP_ENV> → .env.local`，进程 env 最高；secret 默认 `********`。

Assay 建议：统一 `ASSAY_*` 解析器 + `assay doctor --json`；secret 值类型 `toString()` 恒为掩码；`assay config set K=V --save=dotenv:.assay.env.local` 幂等 upsert、保留注释、POSIX 写 0600。

---

## 七、三档吸收清单

### ✅ 明确值得吸收（按投入产出排序）

| # | 内容 | 映射到 Assay | 预估 |
|---|---|---|---|
| 1 | Metric 结果契约（score/threshold/reason/breakdown/skip/cost） | `assay-core/metrics` 新增 `Metric`/`MetricResult`；`CaseResult` 增加 metrics；映射为 CheckResult | 3-5 天 |
| 2 | 确定性 agent 指标：Tool Permission、Tool Correctness、Loop Detection | 扩 `MultiAgentMetrics` + `Rules` | 4-6 天 |
| 3 | Trace→prompt 渲染器（脱敏、compact、timed） | `assay-core/trace` 新增 `TraceRenderer` | 2-3 天 |
| 4 | G-Eval-lite（criteria→steps→score/reason，strict 0/1） | `LlmReviewer` 旁新增 `GevalMetric` | 3-5 天 |
| 5 | QAG verdict 循环 + 空集策略显式化 | `assay-core/metrics/qag` | 2-3 天 |
| 6 | Metric 级缓存（键含 trace 哈希，修掉 deepeval 的 bug） | store 层 + CLI `--metric-cache` | 3-5 天 |
| 7 | 三态/部分结果语义（SKIP 显式保留） | `CaseResult` + 报告 | 2-3 天 |
| 8 | judge 成本归因（未知≠0，接入 BudgetLedger） | `MetricResult` + `CostEstimator` | 2-3 天 |
| 9 | Skills 包（Claude/Cursor） | 仓库根 `skills/` + `.claude-plugin` | 1-2 天 |
| 10 | 合成用例（去重 + 质量门 + provenance） | 新 `assay-synth`，只喂 improvement set | 5-8 天 |

### 🟡 简化后吸收

- **Task Completion / Plan Adherence / Step Efficiency**：只做"先抽 task/plan/outcome 再打分"；抽不到时显式 SKIP，不学 deepeval 空计划给 1.0。
- **GEPA/COPRO**：作为 `evolve-auto` 的候选生成器，保留 Assay 的 accept/reject 终审权。
- **对抗 case 生成**：只借分类法，模板 + 固定种子，不做多轮升级攻击。
- **对话模拟器**：等支持会话级 trace 后再做；先线性多轮 + 停止策略。
- **Benchmark 适配器**：只支持本地 JSONL，不做在线下载。
- **富控制台报告**：只做指标聚合表 + failing 详情，不做 Rich 树/emoji。
- **TS 客户端**：只生成类型 + `run/getRun/listRuns`，不移植评估引擎。

### ❌ 明确不吸收

- SaaS 耦合：Confident 上传、annotation、dataset push/pull 作为核心路径（最多做可选 `ResultPublisher` 适配器）。
- Python 运行时模型：asyncio/Semaphore/ContextVar/portalocker、import 即插桩、猴子补丁、模块级单例。
- System One/Jev、Arena、多模态、语音指标、DAG 反射实现、RAG/ragas/BERTScore 重依赖。
- 四个已知缺陷的原始做法：skip 静默消失、缓存键不含 trace/tools、空集默认满分、`score_breakdown` 不持久化。

---

## 八、路线图与验收

### 第一步：指标契约 + 一个确定性垂直切片

> **实施状态（2026-11-18）：已完成。** 新增 `Metric`/`MetricContext`/`MetricEvaluator`/`ToolPermissionMetric`
> 与 `MetricStatus`/`MetricResult`；`CaseResult.metrics` 落库；`EvaluationEngine` 在规则之后执行指标；
> `Stage.METRICS` 已接入 OTel/Micrometer 监听器；新增 23 项测试，`mvn -o -B -q verify` 通过。
> CLI 参数、指标缓存、报告展示与 G-Eval-lite 已在第二步完成。

目标不是"把指标层建完"，而是用最小代价验证契约：**一个指标从定义、执行、落库、门禁到测试全链路打通**。选定 `ToolPermissionMetric`，因为它是纯确定性、无 LLM、无需新依赖，且能覆盖 PASS/FAIL/SKIP/ERROR 四种状态。

#### 新增文件

```
assay-core/src/main/java/io/assay/metrics/
  Metric.java                 // 接口：name() / defaultLevel() / measure(ctx)
  MetricContext.java          // EvalCase + NormalizedTrace + List<NormalizedTrace>
  MetricEvaluator.java        // 执行指标列表；MetricResult -> CheckResult 映射
  ToolPermissionMetric.java   // 首个内置指标

assay-core/src/main/java/io/assay/model/
  MetricStatus.java           // PASS / FAIL / SKIP / ERROR / NOT_EVALUATED
  MetricResult.java           // 结果记录 + 静态工厂 pass/fail/skip/error
```

> 实际落点说明：`MetricStatus`/`MetricResult` 放在 `io.assay.model` 而不是 `io.assay.metrics`。
> 因为 `CaseResult` 需要持有 `List<MetricResult>`，放在 model 可以避免 `model ↔ metrics` 的包级循环依赖；
> `Metric`/`MetricEvaluator` 仍然只在 `metrics` 包，依赖方向保持单向（metrics → model）。

`MetricResult` 最小字段：

```java
public record MetricResult(
        String name,
        MetricStatus status,
        double score,                 // [0,1]；SKIP/ERROR 时为 NaN
        double threshold,
        String reason,
        Map<String, Object> breakdown,
        List<Map<String, Object>> evidence,
        long inputTokens,
        long outputTokens,
        java.math.BigDecimal costUsd,
        String model,
        boolean cached) {}
```

工厂方法负责校验：`PASS/FAIL` 必须 `0 <= score <= 1` 且有限；`ERROR` 必须带 message；`SKIP` 必须带 reason。`toJson()` 输出 `status`、`score`、`threshold`、`reason`、`breakdown`、`evidence`，为后续报告与缓存留位。

`ToolPermissionMetric` 语义：

- 扫描 `events` 中 `module="tool"`、`action="call_start"`，工具名取 `fields.tool_name / name / tool`；
- 违规 = 命中 `deniedTools`，或 `allowedTools` 非空且不在其中；另外把 `action="all_tools_denied"` 计为违规事件；
- `toolCalls == 0` → `SKIP(reason="no tool calls")`，不进入通过率；
- `score = 1 - violations / toolCalls`，默认 `threshold = 1.0`（权限问题不容忍部分通过）；
- `breakdown` 至少包含 `tool_calls / violations / denied_events / violating_tools`；`evidence` 逐条列出违规工具与原因；
- `defaultLevel()` 返回 `"hard"`——权限违规应阻断，而不是只告警。

#### 修改文件

| 文件 | 改动 |
|---|---|
| `assay-core/.../model/CaseResult.java` | 增加 `List<MetricResult> metrics` 字段、兼容构造器、`setMetrics`、`toJson()` 输出 `metrics` |
| `assay-core/.../engine/EvaluationSettings.java` | 增加 `List<Metric> metrics` 组件（默认 `List.of()`），扩展 `Builder.metrics(...)` |
| `assay-core/.../engine/EvaluationEngine.java` | 增加 `metrics` 字段；五个构造器透传；`evaluateCase` 在 `evaluateRules` 之后、LLM 复核之前执行指标，并把映射出的 `CheckResult` 追加进 `checks` |
| `assay-core/.../engine/EvaluationListener.java` | `Stage` 增加 `METRICS`（OTel/Micrometer 均为字符串化处理，无需改动） |
| `EvaluationSettingsTest` 等 6 处构造调用 | 补 `List.of()` 参数 |

映射规则（`MetricEvaluator.checks`）：

| MetricResult | CheckResult |
|---|---|
| `PASS` | `layer="metric"`, `passed=true`, `actual=score`, `expected=threshold` |
| `FAIL` | `layer="metric"`, `passed=false`, level 取 `metric.defaultLevel()` |
| `SKIP` | 不生成 CheckResult；`metrics` 中保留，不计入通过率 |
| `ERROR` | `layer="metric"`, `passed=false`, level=`hard`, `message=error` |
| `NOT_EVALUATED` | 同 SKIP，但 reason 标注未执行原因 |

#### 测试

- `MetricResultTest`：分数边界（0、1、越界、NaN、Infinity）、工厂校验、JSON 往返；
- `ToolPermissionMetricTest`：全允许、命中 deny、allowlist 缺失、`all_tools_denied`、无工具调用 → SKIP、阈值相等；
- `MetricEvaluatorTest`：四种状态到 CheckResult 的映射，SKIP 不产生 check；
- `EvaluationEngineMetricTest`：构造带违规工具的 trace，验证 `CaseResult.metrics` 存在、`hardPass()` 为 false、`results.json` 含 `metrics`；
- 既有 365 项测试不回归，`mvn -B -q verify` 通过。

#### 明确不在第一步

CLI 参数与 JSON 指标配置、metric 缓存、G-Eval、TraceRenderer、报告渲染、Dashboard 展示。第一步只保证**契约与执行链路成立**，配置面在第二步接入。

#### 完成标志

- 新增 4 个类、1 个枚举和 2 个结果类型，核心零新增依赖；
- 一个 case 的结果里能同时看到 `checks` 与 `metrics`，且 `metrics` 可解释（score/reason/breakdown）；
- `SKIP` 与 `ERROR` 的语义被测试固定，不会静默变成"通过"。

### Phase 1（1-2 周）：指标契约与确定性指标

**交付**

- `Metric` / `MetricResult` / `MetricContext` / `LlmJudge`；
- `TraceRenderer`（full/compact/timed + 脱敏）；
- Tool Permission、Tool Correctness、Agent Loop Detection；
- G-Eval-lite 与 QAG；
- `CaseResult.metrics` 与 CheckResult 映射；
- `--no-llm` 下全部确定性指标可运行。

**验收**

- 新增指标均有单测与边界用例；既有 365 项测试不回归；
- `results.json` / `report.md` 兼容旧格式，新增字段有文档；
- `mvn verify` 通过（Checkstyle/SpotBugs 0 告警）。

### Phase 2（约 1 周）：执行语义与成本

> **实施状态（2026-11-18）：第一步范围内的三项已落地。**
> 已实现：`--preset`/`--metrics`/`--metric-cache`（run/release/submit，队列 payload 会透传）、
> `MetricConfig` 严格解析、`MetricPresets`（`none`/`agent-safety`，后者含 `tool_permission` 与
> `loop_detection`）、`FileMetricCache` 内容寻址缓存、
> `metric_stats` 聚合与报告表，以及 **G-Eval-lite**（`LlmJudge` + `GevalMetric` + `TraceRenderer` +
> `OpenAiCompatibleJudge`）。judge 的 token 已通过 `BudgetLedger.chargeMetrics` 计入运行预算，
> resume 时会从已落盘的 `metrics` 回填；未知成本单独计数，不会当成 0。
> 尚未做：超时预算推导、Dashboard 指标历史；HTTP Server 的提交路径暂未接线。

**交付**

- Metric 缓存（键含 trace 哈希与模板版本）；
- PASS/FAIL/SKIP/ERROR/NOT_EVALUATED 五态；
- judge 调用接入 `BudgetLedger`，报告展示每指标成本；
- 超时预算推导与配置校验；
- `report.md` / Dashboard 的指标聚合与历史趋势。

**验收**

- 缓存命中/失效各有测试，改配置或改 trace 必须失效；
- `budget_exhausted` 与 `SKIP` 不污染通过率；
- 缓存运行与首次运行的分数一致，成本显示为 0 且标注 `cached`。

### Phase 3（约 2 周）：数据与闭环

**交付**

- `assay-synth`：文档/轨迹合成、去重、质量门、provenance；
- 对抗 case 生成（分类法 + 固定种子 + SafetyGate）；
- GEPA-lite / COPRO 接入 `evolve-auto`；
- `EvolutionPolicy` 增加 `syntheticRatioCap`、`requireProvenance`、`safetyFloor`。

**验收**

- 合成用例只进 improvement set 有测试固定；
- 低质量用例被丢弃/隔离并记录原因，不再"重写后保留"；
- 优化器每轮候选可复现（同 seed 同结果）；
- accept/reject 报告按 synthetic/real 分层。

### Phase 4（按需）：追踪、集成与交付

**交付**

- OTel 薄语义层 + `FrameworkAdapter`/`OtelSpanMapper` SPI；
- LangChain4j / Spring AI 适配器（至少一个）；
- skills 包；
- `assay doctor --json` 与配置溯源；
- 最小 TS 客户端（可选）。

**验收**

- 显式安装/卸载，无类加载副作用；
- 脱敏默认生效，可配置关闭；
- skill 能在 Claude Code / Cursor 中被触发并正确引用 CLI。

---

## 九、风险与反例

| 风险 | 说明 | 对策 |
|---|---|---|
| **抄袭把缺陷一起抄进来** | deepeval 的 skip 消失、缓存键漏 trace/tools、空集满分、breakdown 不落库 | 第九节逐条反着做，并有测试固定语义 |
| **指标被"刷分"** | 模型指标只看最终答复，Agent 可能用冗长输出骗分 | 规则层与指标层并存；关键场景 hard gate 仍用确定性规则；报告展示 breakdown |
| **合成数据污染回归集** | 合成 case 进入 regression/holdout 会让演化结论失真 | 只进 improvement set；`syntheticRatioCap` + `requireProvenance` |
| **judge 成本失控** | 每个 case 多次 LLM 调用 | 缓存 + 预算预扣 + `includeReason` 分级 + `compact` 渲染 |
| **确定性被破坏** | 进化/合成引入随机 | 所有随机源显式 `seed`；同 seed 同结果有测试 |
| **过度设计** | DAG、System One、多引擎决策 | 明确列入"不吸收"，避免为抽象而抽象 |
| **核心依赖膨胀** | 指标层引入 JSON/HTTP 库 | 复用 `io.assay.json` 与 `LlmReviewer`；模型调用只在 `assay-runtime` |

---

## 十、附录：deepeval 关键文件索引

| 主题 | 文件 |
|---|---|
| 指标基类与结果字段 | `deepeval-main/deepeval/metrics/base_metric.py:111-203` |
| QAG 判定循环 | `deepeval-main/deepeval/metrics/utils/qag.py:58-138` |
| G-Eval | `deepeval-main/deepeval/metrics/g_eval/g_eval.py:320-342`；`metrics/g_eval/utils.py:35-50,346-413` |
| Loop Detection | `deepeval-main/deepeval/metrics/agent_loop_detection/agent_loop_detection.py:294-549` |
| Tool Correctness | `deepeval-main/deepeval/metrics/tool_correctness/tool_correctness.py:518-603` |
| Task Completion | `deepeval-main/deepeval/metrics/task_completion/task_completion.py:172-232` |
| Plan Adherence | `deepeval-main/deepeval/metrics/plan_adherence/plan_adherence.py:115-279` |
| 执行与超时 | `deepeval-main/deepeval/evaluate/execute/e2e.py:483-711`；`config/settings.py:1094-1174` |
| 缓存 | `deepeval-main/deepeval/test_run/cache.py:141-184` |
| SQLite schema | `deepeval-main/deepeval/sqlite_store/store.py:58-158` |
| 合成器 | `deepeval-main/deepeval/synthesizer/synthesizer.py:966-1149,1516-1551` |
| GEPA 优化器 | `deepeval-main/deepeval/optimizer/algorithms/gepa/gepa.py:166-282`；`optimizer/policies.py:62-229` |
| 追踪模型 | `deepeval-main/deepeval/tracing/tracing.py:843-1105`；`tracing/types.py:86-168` |
| OTel 适配 | `deepeval-main/deepeval/tracing/otel/converter.py:101`；`otel/adapters/base.py:10` |
| 配置与脱敏 | `deepeval-main/deepeval/config/settings.py:257,1754-1818`；`cli/diagnose/diagnose.py:151,482` |
| AI skills | `deepeval-main/skills/`；`.claude-plugin/plugin.json` |

---

## 与现有文档的关系

- 本文补充 `docs/assay-improvement-plan.md` 的「4.7 P3 评测深度」：原计划只提到工具链序列规则与评估器校准，本文把"语义评分指标"独立成一条主线，并给出接口与验收。
- README 的「四层评测」在实现 Phase 1 后应增加一句：结构/行为层之后可挂**确定性指标**与**模型指标**，两者都写入 `CaseResult.metrics`，由用例配置决定是否参与硬门禁。
- 本文不承诺时间点；工作量是相对估计，随现有测试强度与评审要求上下浮动。
