# Assay 面试准备与简历写法

> 适用场景：Agent 应用 / AI 平台 / 评测质量 / Java 后端岗位。
> 项目事实：Java 17、Maven Reactor、核心零运行时依赖；78 个主代码文件、约 1.3 万行主代码、32 个测试文件、约 5,000 行测试；211 项核心 Java 测试通过（1 项按环境跳过）+ 10 项 Node 测试 + 4 项 Spring Boot Starter 测试 + 2 项 Micrometer 测试 + 2 项 OTel 测试；GitHub Actions Windows/Linux 双平台。
> 仓库：https://github.com/Ricardu255/Assay
> 阅读顺序：第一至四部分用于项目介绍；第五部分是 30 题速查；第八部分是 22 题深答，面试前建议按第八部分逐题演练；第六部分是简历写法；第十部分是代码级深挖（TraceDiff / 门禁 / 恢复 / JDBC / Spring Boot Starter / Micrometer / OTel），用于应对“再往下讲一点”的追问；第十一部分是可观测性专项追问（13 题），面试前可单独过一遍。

---

## 一、项目定位

### 1.1 一句话

Assay 是一个与领域解耦的 Agent 自动评测、诊断、人工审核、回归和版本演化框架：把候选 Agent/Skill 的 Trace 归一化，执行四层评测，输出硬门禁、软告警、首错诊断和 accept / reject / rollback 决策，但最终收货权始终在人手里。

### 1.2 30 秒口述版

“我做过一个叫 Assay 的 Agent 评测框架。它解决的不是 Agent 能不能跑，而是改完之后能不能安全发布、退化能不能定位。它通过适配器把不同 Agent 的 Trace 转成统一契约，跑结构、行为、一致性、反馈四层评测，再用 improvement、regression、holdout 三套数据比较基线和候选，给出 accept / reject / rollback；如果退化，会定位到第一处步骤差异。项目是 Java 17、核心零运行时依赖，文件/JDBC 双存储，211 项测试，Windows/Linux CI 都跑通。”

### 1.3 2 分钟口述版

1. **背景**：Agent 迭代很快，demo 能跑不代表能发布；线上 Trace 很多，但“看 Trace”不等于“做发布决策”。现有观测平台擅长存储和展示，指标库擅长单次打分，缺的是版本对比、发布门禁和退化定位的闭环。
2. **做法**：Assay 用 `ProjectAdapter` 回答四个问题：怎么调用 Agent、怎么把原生 Trace 转成 `NormalizedTrace`、哪些规则是硬门禁、哪些是软质量。核心层只依赖标准 Trace 和规则，不依赖具体 Agent 框架。
3. **评测**：结构层看 Trace 完整性和硬门禁；行为层看步数、重试、延迟、重复步骤和模块错误；一致性层对高价值节点做多次运行；反馈层把隐式负反馈变成人工复核候选。
4. **演化**：baseline 和 candidate 在 improvement / regression / holdout 三个数据集上跑同一套用例，按策略输出 accept / reject / rollback。新增了场景门禁、绝对质量门禁、规则通过率、P95 和样本覆盖。
5. **诊断**：对“基线通过、候选失败”的用例，`TraceDiff` 用 LCS 对齐 `module/action`，定位第一处步骤增删、替换、字段变化、报错或最终输出差异；直接复用已经评测过的 Trace，不重跑 Agent。
6. **结果**：核心零运行时依赖，存储可替换为文件或 JDBC；211 项核心 Java 测试 + 10 项 Node 测试 + 4 项 Spring Boot Starter 测试 + 2 项 Micrometer 测试 + 2 项 OTel 测试；CI 覆盖 Windows/Linux；示例可稳定复现 rollback 和首错定位；还支持零代码接入 normalized / OpenInference / OTLP 外部轨迹。

### 1.4 5 分钟 / 白板顺序

1. **问题**：版本迭代的发布决策缺少可追溯证据。
2. **契约**：`NormalizedTrace` + 四能力适配器。
3. **评测**：四层 + 硬/软门禁。
4. **演化**：三数据集 + 决策 + 门禁。
5. **诊断**：用例状态迁移 + LCS 首错 + 失败聚类。
6. **可靠性**：批式落盘、恢复、文件锁、路径安全。
7. **结果与取舍**：测试、CI、零依赖、单机文件存储。

---

## 二、架构与核心流程

### 2.1 架构图

```text
Case JSONL ──► EvaluationEngine ──► CaseResult / NormalizedTrace
                   │                        │
Adapter ───────────┘                        ├─► ResultStore（JSONL + 锁 + 恢复）
（HTTP / Java / 离线 / OTLP）               ├─► Reporting（results.json / report.md）
                                            └─► Dashboard（只读）

Baseline run ─┐
              ├─► EvolutionEngine ──► comparisons ──► decision
Candidate run ┘        │                  │
                       │                  ├─ case_transitions + TraceDiff
                       │                  ├─ rule_deltas
                       │                  └─ scenario / quality gates / objectives
                       └─► evolution.json / evolution_report.md / Dashboard
```

### 2.2 一次评测的生命周期

1. 读取 JSONL 用例，校验 ID 唯一、内容非空。
2. 适配器调用 Agent，拿到原生 handle。
3. `readTrace` 转成 `NormalizedTrace`。
4. `Rules` 执行结构、行为、一致性、反馈检查。
5. `CaseResult` 汇总硬通过/软告警。
6. `ResultStore` 按批次落盘，运行结束压缩结果。
7. `Reporting` 生成 `results.json`、`report.md`、场景统计、审核队列、few-shot 候选。
8. Dashboard 只读展示。

### 2.3 模块地图

| 模块 | 职责 |
|---|---|
| `engine` | 适配器加载、用例加载、评测执行、release 编排 |
| `model` | `NormalizedTrace`、`TraceEvent`、`Rule`、`EvalCase`、`EvolutionPolicy`、`QualityGate` |
| `rules` | 路径读取、规则匹配、四层检查、规则配置解析 |
| `evolve` | 三数据集演化、决策、规则统计、`TraceDiff` 首错 |
| `autoevolve` | 沙箱内诊断、生成候选、评测、检查点 |
| `store` | `Results` 接口、JSONL 存储、锁、恢复 |
| `report` | Markdown/JSON 产物 |
| `trace` / `opentrace` / `http` | 外部轨迹接入 |
| `process` / `workspace` | 进程、容器、文本候选沙箱 |
| `audit` / `diff` / `selection` | 范围审计、Git Diff、测试集选择 |
| `web` | 只读 Dashboard |

---

## 三、核心设计决策与取舍

| 决策 | 原因 | 代价 |
|---|---|---|
| 核心零运行时依赖 | 可移植、易部署、无供应链负担 | JSON、HTTP Server、存储都要自己写，测试量更大 |
| `ProjectAdapter` 四能力契约 | 领域解耦，不绑定 LangChain/OTel/厂商 | 适配器需要自己做 Trace 归一化 |
| `Results` 接口 + 文件/JDBC 双实现 | 单机零依赖、断点恢复简单；需要数据库时可切换 | JDBC schema 需自己迁移；锁/事务语义要按数据库验证 |
| 可选 Spring Boot Starter | 企业集成友好，不污染核心 | 扩展模块，已纳入 reactor；发布仍需单独配置 |
| `EvaluationListener` + Micrometer/OTel | 可观测、可扩展，默认 no-op | 指标基数/span 层级需要设计；细粒度 adapter/规则 span 还没做 |
| LLM 只做建议 | 可复现、可审计、抗 prompt injection | 语义判断能力有限，需要人工复核 |
| 诊断只做“第一处可见差异” | 可解释、可测试、不重跑 Agent | 不是因果根因，非确定性轨迹可能误报 |
| 只读 Dashboard | 安全、无需构建、报告不可被界面改写 | 没有在线编辑和多人协作 |
| 硬/软门禁分离 | 发布判定必须确定，质量问题可人工看 | 规则设计需要领域参与 |

---

## 四、本次迭代新增内容（可作为“最近做了什么”）

- **0.6 诊断闭环**：`TraceDiff` 用例级首错、`case_transitions`、失败聚类、报告和 Dashboard 展示、故意退化夹具。
- **0.7 门禁与统计**：`QualityGate` 绝对门禁（最低通过率、最大回归数、最大关键回归、最小样本）、`MetricObjective.maximum`、P50/P95/P99、规则通过率 `rule_stats` / `rule_deltas`。
- **0.8 外部轨迹**：`trace_file` 适配器，支持 normalized、OpenInference、OTLP attribute array、Phoenix `traces` / `resourceSpans`；按 `case_id` 校验覆盖，缺失用例硬失败。
- **Dashboard**：诊断文案中文化、回归用例统计卡、规则表默认只显示变化、类别 badge 分色、关键用例标记。
- **存储**：`Results.RunLock` 抽象、`JdbcResultStore`、`Stores.open`；CLI 支持 `--store jdbc:...`，H2 契约测试 5 项 + CLI JDBC 测试 1 项。
- **集成**：`extensions/assay-spring-boot-starter`，文件/JDBC 自动配置、`EvaluationEngine` 装配；3 项上下文测试。
- **交付**：`Dockerfile`、CI starter/metrics 测试/镜像构建；1,000 条轻量用例 benchmark（约 1,100 cases/s，非生产容量承诺）。
- **可观测**：零依赖 `EvaluationListener` SPI（run/case/batch 生命周期，默认 no-op，listener 异常不影响评测）；`extensions/assay-metrics-micrometer` 输出指标（标签不含 run_id/case_id），`extensions/assay-tracing-otel` 输出 run/case/batch span，失败 run 通过 `onRunFailed` 关闭 span。

---

## 五、面试题库（30 题）

### A. 项目与动机

**A1. 为什么做 Assay？和 LangSmith、Phoenix、Ragas 有什么区别？**

- 一句话：观测平台解决“看得到”，指标库解决“打得分”，Assay 解决“能不能发、退化在哪”。
- 展开：LangSmith/Phoenix 强在 Trace 存储、实验和可视化；Ragas/DeepEval 强在单次指标。Assay 消费这些 Trace，补的是版本对比、三数据集发布门禁、首错诊断和人工最终结论。它不替代观测平台，而是接在观测之后做发布决策。
- 追问：为什么不用现成平台？答：可以接，但发布策略、硬/软门禁、人工审核和领域规则需要可定制的闭环；Assay 的适配器可以接 HTTP、Java、离线轨迹。

**A2. 一句话介绍 Assay，为什么叫这个名字？**

- 一句话：像冶金厂对一批金属取样化验，Assay 对候选 Agent 取样、过门禁、给结论，但收货决定权在人手里。
- 展开：它不是“自动改代码/自动上线”的工具，而是产出证据和判定，避免把“测试通过”直接等同于“可以发布”。

**A3. 这个项目最难的地方是什么？**

- 一句话：不是规则引擎，而是让决策可复现、可追溯、不混数据。
- 展开：要保证 baseline/candidate 可比（版本身份、数据集、配置），缺失指标不能变 0，异常退出能恢复，隐藏回归不能只看通过率；诊断还要复用同一批 Trace，不能重跑。
- 追问：最难的 bug？见 H5。

### B. 架构与契约

**B1. 为什么要 `ProjectAdapter` 四能力？为什么不直接接 OTel？**

- 一句话：四个问题把“领域”和“框架”切开：怎么调用、怎么读 Trace、哪些硬门禁、哪些软质量。
- 展开：HTTP、Java SDK、子进程、离线文件都只是适配器实现；框架核心只依赖 `NormalizedTrace` 和 `Rule`。OTel 是 Trace 来源之一，不是框架的依赖。
- 追问：适配器会不会很重？答：领域项目只做归一化，评测、存储、报告、Dashboard 都复用。

**B2. `NormalizedTrace` 为什么这样设计？**

- 一句话：一个稳定契约，既保留完整性字段，又给领域规则留 `fields`。
- 展开：`trace_id` / `final_output` 是完整性硬条件；`events` 给行为层；`fields` 给领域规则；`feedback` 放隐式信号；`target_type/id/version` 用于演化版本匹配；`raw` 保留原始证据。
- 追问：为什么不直接存原始 JSON？答：评测层需要稳定路径和统一语义；原始 JSON 放 `raw`，不参与规则。

**B3. 四层评测怎么分工？硬门禁和软告警怎么划？**

- 一句话：结构/行为是确定性门禁，一致性/反馈只产生复核候选。
- 展开：结构层看 Trace 完整性和硬门禁；行为层看步数、重试、延迟、重复步骤、模块错误；一致性层对多次运行做对比；反馈层把显式负反馈、重复提问、改写问题变成候选。
- 追问：为什么一致性不直接判失败？答：非确定性 Agent 的差异需要人看，不能自动判 badcase。

**B4. 规则为什么用“路径 + 操作符”，而不是写 Java 断言？**

- 一句话：规则是数据，才能序列化、审计、给 HTTP 用户零代码配置。
- 展开：`actual` 从 Trace 读路径，`expected` 从用例 `expected` 读路径或用固定 `value`；支持 `eq/ne/contains/not_contains/min/max/in/exists`。工具链、参数这类复杂逻辑可以让适配器暴露成 `fields.tool_chain`，再用规则比较。
- 追问：复杂断言怎么办？答：写适配器或在领域侧计算成字段；框架不引入脚本引擎。

### C. 评测与门禁

**C1. 为什么 LLM 不能参与硬门禁？**

- 一句话：硬门禁必须可复现、可审计、抗注入，LLM 做不到稳定。
- 展开：LLM 输出有随机性、成本、延迟和 prompt injection 风险；Assay 让 LLM 只产出语义分析、疑似模块、修改建议和 few-shot 候选，报告里明确标注“非权威”。
- 追问：那语义质量怎么评？答：软质量规则 + 人工复核；后续可接校准过的 LLM-as-judge，但不进硬门禁。

**C2. 缺失指标为什么保留 `null`，不补 0？**

- 一句话：缺失不是零；补 0 会把“没测到”伪装成“表现很好”。
- 展开：`metric_coverage` / `objective_coverage` 记录 `sample_count`、`missing_count`、`case_count`；必需目标覆盖不全直接 rollback；显式测量到的 0 仍然是 0。测试覆盖了“缺失”和“真实零值”的区分。
- 追问：聚合溢出？答：非有限结果视为缺失，不产生 Infinity。

**C3. accept / reject / rollback 的判定逻辑？**

- 一句话：rollback 是“破坏了不能坏的”，reject 是“没证明变好”，accept 是“变好且保护集没超退化”。
- 展开：rollback 还覆盖版本身份不匹配、必需指标缺失、场景门禁/质量门禁不通过；reject 主要来自 improvement 未达到最低改善；所有理由都写入 `reasons`，报告和 Dashboard 可追溯。

**C4. 为什么需要 improvement / regression / holdout 三个集？**

- 一句话：一个证明“改好了”，一个防止“改坏了”，一个防止“只对生成候选的样本有效”。
- 展开：improvement 是候选声称要解决的问题；regression 是已经确认、不能复发的历史能力；holdout 是候选生成过程没见过的留出样本。baseline 和 candidate 必须跑同一套用例，并校验 target 身份/版本。

**C5. scenario gates、quality gates、objectives 的区别？**

- 一句话：scenario 看切片，quality 看候选自身是否可接受，objectives 看相对基线的数值变化。
- 展开：scenario gate 可按场景设最低样本、最低通过率、允许退化；quality gate 按角色设最低硬通过率、最大回归数、最大关键回归数、最小样本；objective 支持 mean/sum/min/max/p50/p95/p99、最小改善、最大退化和绝对上限 `maximum`。
- 追问：为什么要三套？答：它们回答不同问题，不能用一个总通过率替代。

**C6. 怎么防“通过率不变但用例一进一出”的隐藏回归？**

- 一句话：逐用例状态迁移 + 回归条数门禁，而不是只看聚合通过率。
- 展开：`case_transitions` 把用例分成稳定通过、回归、恢复、持续失败；`maximum_task_regressions` / `maximum_critical_task_regressions` 按条数拦。默认 `require_regression_pass` 能兜住 regression 集的硬失败，但在 holdout/improvement 或放宽策略时，条数门禁更明确。
- 追问：关键用例怎么标？答：用例 `metadata.critical = true`。

**C7. P95 怎么算？小样本怎么办？**

- 一句话：线性插值，同时报告有效样本数；小样本用最小样本门禁和置信区间约束。
- 展开：P95 用排序后线性插值（R-7），不是简单取第 95% 个；报告展示 `样本数/用例数`；quality gate 有 `minimum_samples`；场景通过率用 Wilson 区间，避免把 1/1 读成 100% 的精确能力。

### D. 演化与诊断

**D1. `TraceDiff` 怎么对齐？为什么不用逐位置比较？**

- 一句话：按 `module/action` 做 LCS 对齐，插删一步不会把后面全部误判。
- 展开：先找最长公共子序列作为锚点，未匹配区间再判断步骤新增/缺失/替换/重排；匹配上的步骤比较 error、status、fields；所有差异按候选步骤排序，取第一处。超过 300 步退化为按位置比较，并在 `alignment` 标注。
- 追问：为什么不用编辑距离？答：LCS 足够、可实现、结果可解释；编辑距离还要定义替换代价。

**D2. 为什么忽略 span_id、时间字段？会不会漏问题？**

- 一句话：这些字段每次运行都会变，不代表行为变化；忽略是为了让首错有意义。
- 展开：默认忽略 `span_id`、`parent_span_id`、时间/耗时字段；用例可用 `metadata.trace_diff_ignore` 追加。耗时问题由指标和门禁负责，不靠轨迹 diff；诊断是辅助证据，不是硬门禁，误报/漏报由人工复核兜底。

**D3. 为什么诊断不重跑 Agent？**

- 一句话：复用已经评测过的 Trace，证据和报告才是同一批。
- 展开：重跑对非确定性/线上 Agent 不成立，也可能产生和原报告不一致的轨迹；Assay 的 `case_transitions` 直接读 baseline/candidate 的 run 结果，成本低、可复现。

**D4. 轨迹完全一致但用例失败，怎么解释？**

- 一句话：归为 `no_visible_divergence`，说明失败来自相同数据上的规则或运行完整性问题。
- 展开：可能原因包括规则检查的字段不在 steps/final_output 里、重复 trace id、trace 缺失、适配器执行错误。报告会保留失败规则和 Trace 证据，提示人工复核。

**D5. 首错定位的准确性/置信度怎么保证？**

- 一句话：不造“置信度”，用确定性类别、字段路径和测试保证可解释。
- 展开：`step_added/removed/changed/reordered/fields/error/status/final_output/trace_fields/missing_trace` 都有明确语义；测试覆盖插入不级联、字段变化、删除步骤、缺失 Trace、输出变化；用故意退化的夹具验证。它是“第一处可见差异”，不是因果根因，结论交给人工。

**D6. 外部轨迹怎么做到零代码？OpenInference/OTLP 怎么兼容？**

- 一句话：`trace_file` 适配器读 JSON，按 `case_id` 关联用例，不需要写 Java。
- 展开：normalized 格式就是 `NormalizedTrace.toJson()`；OpenInference 读 `openinference.span.kind`、`tool.name`、`input.value`、`output.value`、token 等语义属性；OTLP 读 `resourceSpans/scopeSpans` 和 `{key, value:{stringValue}}` 属性数组。用例缺失时硬失败，不静默跳过；`target_*` 覆盖目标身份，保证 evolve 版本匹配。

### E. 可靠性与并发

**E1. 断点恢复怎么保证不混数据？**

- 一句话：run identity + case manifest 哈希 + 数据集/版本身份校验，任何不一致都拒绝复用。
- 展开：每个 case 计算内容哈希，恢复时区分“新增 case”和“内容被改过”；结果按最多 32 条批次落盘，未提交批次在恢复时重跑；只允许最后一行未写完时截断；中间损坏明确报错。

**E2. 并发模型是什么？**

- 一句话：有界线程池 + 两倍 in-flight 窗口 + 适配器并发上限。
- 展开：`workers` 控制线程数，`maxInFlight = 2 × workers` 避免慢 case 饿死队列；批次最多 32 条；适配器可实现 `maxConcurrency`；结果写入用原子文件操作；同一 run 用文件锁保证只有一个持有者。

**E3. 为什么用文件锁而不是数据库？**

- 一句话：零依赖、单机可移植、足够覆盖当前场景；存储层用 `Results` 接口隔离。
- 展开：文件和锁的代价是没有分布式协调和水平扩展；如果要上团队服务，可以换 JDBC/SQLite/对象存储实现，引擎不用改。跨进程锁的测试会真的启动第二个 JVM 抢同一把锁。

**E4. 半行 JSON / UTF-8 字符写到一半怎么恢复？**

- 一句话：只截断最后一行未完成的记录，完整但缺换行的补分隔符，中间损坏不猜。
- 展开：恢复时会处理截断尾部、UTF-8 半字符、缺少换行和再次中断；中间日志损坏或缺少 case 身份会明确报错，避免生成虚假结果。适配器 jar 会被整体读入内存，不长期占用文件句柄，Windows 上可以直接替换。

### F. 安全与沙箱

**F1. evolve-auto 怎么防路径逃逸、保护基线？**

- 一句话：路径白名单 + 暂存区 + manifest + 哈希校验 + 最终路径再验证。
- 展开：候选路径必须是 `/` 分隔的相对路径，拒绝盘符、冒号、控制字符、尾随空格/点和符号链接；多文件候选先在 `.assay-staging` 暂存区完成复制、修改、哈希和 manifest，再整体移动；已提交候选会校验哈希；回滚不会覆盖基线目录。

**F2. 进程/容器隔离怎么做？**

- 一句话：按信任等级选进程 Runner 或容器 Runner；超时杀整棵进程树，输出有上限。
- 展开：`AgentProcessRunner` 超时终止整棵进程树并保留 stdout/stderr，单流默认最多 1 MiB，超限立即终止；`ContainerRunner` 默认禁网、只读挂载候选目录、只读根文件系统、移除 capabilities、限制 CPU/内存/PID。

**F3. Dashboard 安全怎么保证？**

- 一句话：只读、路径包含校验、符号链接拒绝、补丁 SHA-256 校验。
- 展开：artifact id 是 base64 的受限路径，服务端校验仍在报告根目录内、拒绝符号链接、限制 64 MiB；审计补丁读取时核对报告里的 `diff_sha256`，不一致拒绝展示；没有写接口，只监听本机。

### G. 工程化与测试

**G1. 为什么 Dashboard 不用 React？怎么测试？**

- 一句话：零构建、零依赖、由 Java 直接托管；纯函数用 Node 测试，交互用浏览器检查。
- 展开：原生 HTML/CSS/JS + 主题变量；`dashboard-core.js` 放校验、diff 解析、诊断文案等纯函数，`node --test` 覆盖；Java 端 `DashboardServerTest` 覆盖路由和产物读取；支持深浅色、键盘导航、URL 状态恢复。
- 追问：组件复用怎么办？答：当前页面规模不需要组件框架；如果继续扩展，可以再引入构建链。

**G2. 211 项测试怎么组织？最有价值的是哪些？**

- 一句话：重点测失败模式，不是只测 happy path。
- 展开：评测闭环、四层规则、运行完整性/恢复、并发（真实第二 JVM 抢锁）、演化决策、自动演化预算/检查点、进程树/容器安全参数、测试集选择、范围审计、OpenAI Trace、外部轨迹、JDBC 存储、监听器/失败回调、Dashboard。最有价值的是恢复、并发、进程树、路径安全、JDBC 契约和诊断边界。
- 追加：Spring Boot Starter 另有 4 项 `ApplicationContextRunner` 测试；Micrometer 2 项（成功 + 失败 run）；OTel 2 项（父子 span + 失败关闭）。

### H. 取舍与反思

**H1. 项目有什么不足？下一步做什么？**

- 一句话：单机文件存储、没有多租户/权限、诊断是辅助证据、没有生产流量验证。
- 展开：下一步可以换 `Results` 的 JDBC/对象存储实现、做 Trace 接入服务、增加趋势和回归看板、校准 LLM-as-judge（仍不进硬门禁）、policy-as-code、PR 评论和发布流水线集成。

**H2. 数据量大了怎么办？**

- 一句话：先换存储和接入层，不要把单机文件方案硬撑成分布式。
- 展开：`Results` 接口可以换 JDBC/对象存储；Trace 接入可以流式处理；`TraceDiff` 有 300 步上限，超限退化；结果分页；评测 worker 可以独立部署；锁要换成数据库/分布式锁。

**H3. Agent 是非确定性的，评测还有意义吗？**

- 一句话：有，但不能用一次运行下结论。
- 展开：高价值节点做多次运行和一致性检查；报告置信区间和样本覆盖；硬门禁只放在稳定信号上；场景门禁防止小样本被平均；结论进入人工复核。非确定性不是不评测，而是要承认不确定性。

**H4. 最大的取舍是什么？如果重做会怎么改？**

- 一句话：零运行时依赖换来了可移植性，也换来了更多自研代码和测试。
- 展开：如果重做，会更早定义 Trace schema/versioning，把决策引擎从 CLI 中抽成纯函数，给 `Path`/`ValueOps` 加属性测试，并更早引入数据库存储。但零依赖对本地演示和可移植性仍然有价值。

**H5. 讲一个最难的 bug。**

- 场景：断点恢复时，最后一行可能只写了一半，甚至 UTF-8 字符写到一半；简单按行解析会报错或丢数据。
- 做法：只允许最后一行未完成时截断；完整但缺换行的补分隔符；UTF-8 半字符恢复；中间损坏明确报错，不猜。
- 结果：测试覆盖截断尾部、半字符、缺换行、再次中断；恢复后不会把两次实验混在一起。
- 追问：怎么验证？答：用临时目录构造损坏文件，断言恢复后的 case 数、内容和身份清单。

### I. 反问清单

- 团队现在怎么做 Agent 发布？是看通过率、人工评审，还是有正式门禁？
- Trace 标准是 OTel/OpenInference/Phoenix，还是自研协议？
- 质量指标里，哪些是硬门禁，哪些只做告警？
- badcase 从线上回流到评测集，链路有多长？
- 这个岗位前三个月最希望解决的质量问题是什么？

---

## 六、简历写法

> 按你给的参考图风格：项目名 + 时间、技术栈、项目简介、核心内容。
> 如果项目是独立完成，可以写“独立设计并实现”；如果是团队项目，改成“负责核心模块设计与实现”。

### 6.1 通用版（推荐直接放简历）

```text
Assay — Agent 评测、诊断与版本演化框架        2026.10 - 至今
技术栈：Java 17、Maven Reactor、JUnit 5、JDBC、Spring Boot Starter、Micrometer、JDK HttpServer、JSON/JSONL、原生 HTML/CSS/JS、Docker、GitHub Actions

项目简介：面向 Agent/Skill 发布前的质量验证，解决“改完能不能发、退化在哪一步”的问题。通过适配器把不同 Agent 的 Trace 归一化，执行四层评测和基线/候选三数据集对比，输出硬门禁、首错诊断与 accept/reject/rollback 决策；最终收货权保留给人。

核心内容：
- 设计领域解耦的 ProjectAdapter 四能力契约（调用、Trace 转换、硬门禁、软质量），支持 HTTP、Java 适配器、离线归一化轨迹和 OpenInference/OTLP 导出，核心零运行时依赖。
- 实现结构/行为/一致性/反馈四层评测与路径化规则引擎，区分硬门禁与软告警；LLM 只产出非权威建议，不参与发布判定。
- 实现 baseline/candidate 在 improvement/regression/holdout 三个数据集上的演化决策，加入 scenario gates、绝对质量门禁、关键回归计数、规则通过率与 P50/P95/P99，覆盖小样本和“通过率不变但用例一进一出”的隐藏退化。
- 实现 TraceDiff 首错定位：基于 LCS 对齐 module/action，识别步骤增删、替换、字段变化、报错和最终输出差异，生成用例状态迁移、失败聚类和可追溯证据，不重跑 Agent。
- 实现运行完整性：`Results` 抽象、文件/JDBC 双存储、批式落盘、断点恢复、case 内容哈希、跨进程锁、trace id 唯一性校验；异常退出后可恢复且不混跑。
- 实现 evolve-auto 沙箱与只读 Dashboard：路径逃逸防护、暂存区/manifest/哈希校验、预算与检查点、进程树终止与容器隔离；Dashboard 支持运行/Trace/规则/演化/审计/发布视图。
- 将主项目、Spring Boot Starter、Micrometer、OTel 扩展统一到 Maven reactor，父 POM 管理 Java/Spring/Micrometer/OTel 版本，根目录 `mvn test` 一次构建全部模块。
- 实现零依赖 `EvaluationListener` SPI 与可选 Micrometer/OTel 扩展：run/case/batch 生命周期指标和 span，标签不带 run_id/case_id，listener 异常不影响评测；失败 run 通过 `onRunFailed` 关闭 span；Starter 自动注入。
- 测试与交付：211 项核心 Java 测试 + 10 项 Node 测试 + 4 项 Spring Boot Starter 测试 + 2 项 Micrometer 测试 + 2 项 OTel 测试，覆盖恢复、并发（真实第二 JVM 抢锁）、JDBC 契约、进程树、容器安全参数、范围审计和外部轨迹；Docker 镜像与 GitHub Actions Windows/Linux 双平台。

项目成果：核心零运行时依赖；211 项核心 Java 测试通过（1 项按环境跳过）+ 10 项 Node 测试 + 4 项 Starter 测试 + 2 项 Micrometer 测试 + 2 项 OTel 测试；1,000 条轻量用例约 1,100 cases/s；示例可稳定复现 rollback 与首错定位。
```

### 6.2 偏 Agent 评测 / 质量岗

把顺序调整为：

1. 四层评测 + 硬/软门禁 + 人工复核。
2. 三数据集演化 + accept/reject/rollback + 场景/质量门禁。
3. TraceDiff 首错 + 用例状态迁移 + 失败聚类。
4. 外部轨迹接入（OpenInference/OTLP）+ EvaluationListener/Micrometer/OTel 可观测性。
5. 运行完整性 + 恢复 + 并发。
6. 测试与 CI。

关键词：Agent Evaluation、LLM Observability、Trace Normalization、Release Gate、Regression Diagnosis、Human-in-the-loop、Evaluation Dataset、Golden Set、Holdout、P95、Wilson Interval。

### 6.3 偏 Java 工程岗

把顺序调整为：

1. Java 17、Maven reactor（app + starter + metrics）、核心零运行时依赖、适配器类加载。
2. `Results` 抽象、文件/JDBC 双存储、批式落盘、断点恢复、跨进程锁。
3. 有界并发、进程树终止、容器隔离、路径安全。
4. 规则引擎、决策引擎、TraceDiff（LCS）。
5. JDK HttpServer + 原生前端、只读 Dashboard、Spring Boot Starter。
6. `EvaluationListener` SPI + Micrometer 指标 + OTel span、基数/父子关系、失败关闭、异常隔离。
7. 211 项 JUnit 测试 + 4 项 Starter 测试 + 2 项 Micrometer 测试 + 2 项 OTel 测试 + Windows/Linux CI。

关键词：Java 17、Maven Reactor、JDBC、Spring Boot Starter、Micrometer、OpenTelemetry、Observability、Metrics Cardinality、Concurrency、File Lock、Crash Recovery、ClassLoader、Process Tree、Container Isolation、Path Traversal、JUnit 5、Docker、GitHub Actions。

### 6.4 更短的一行版（空间不够时用）

“Java 17 实现的零依赖 Agent 评测与版本演化框架，支持 Trace 归一化、四层评测、三数据集演化、首错定位、文件/JDBC 存储、发布门禁和 Micrometer/OTel 可观测性；211 项测试，Spring Boot Starter，CI 双平台。”

### 6.5 可观测性 bullet（可直接复制）

**通用版：**

> 设计零依赖 `EvaluationListener` 生命周期 SPI（run/case/batch，默认 no-op、异常隔离），实现可选 Micrometer 扩展，输出失败率、case P95、批量落盘延迟等指标；标签只用 suite/source/status，避免 run_id/case_id 基数爆炸；Starter 自动注入。

**偏平台/可观测版：**

> 为评测引擎建立可观测性：核心 SPI 解耦 run/case/batch 事件，Micrometer 扩展覆盖吞吐、失败率、case P95、存储写入延迟；通过有限标签和 Timer/Histogram 控制基数，接入 Actuator/Prometheus；listener 故障不影响发布判定。

**注意**：现在可以写“Micrometer 指标 + OTel run/case/batch span”，但不要写“全链路 Trace”或“adapter/规则级 span”；细粒度 span 仍是下一步。

### 6.6 排版建议

- 放在 MiniCode / Ragent 之后，作为“质量保障/工程化”的差异化项目；如果投 AI 平台/评测岗，可以放第一个。
- 技术栈只写有把握被追问的：Java 17、JUnit 5、HTTP、JSON、原生 JS、GitHub Actions。
- 不要写“生产级”“支撑百万级”；这个项目是本地/单机工程，诚实写“零依赖、可移植、可复现”更加分。
- GitHub 链接可选；如果仓库公开，放上去；如果不公开，删掉。
- 项目时间按实际填；如果仍在迭代，写“2026.10 - 至今”。

---

## 七、讲解时的注意事项

1. **先讲问题，再讲架构**：不要一上来念模块。
2. **强调“证据”和“人的决定权”**：这是项目和普通评测脚本的区别。
3. **不要过度承诺**：不说“生产级”“大规模”“准确率 99%”；说“本地可复现、211 项测试、JDBC/Starter/Micrometer/OTel 已落地、1,000 条轻量用例约 1,100 cases/s、示例可复现 rollback”。
4. **主动说取舍**：文件存储 vs 数据库、LLM 不进硬门禁、诊断是辅助证据。面试官更看重你知道边界。
5. **准备一个具体 bug**：恢复/半行 UTF-8 或隐藏回归，按 STAR 讲。
6. **准备一张架构图**：适配器 → 评测 → 演化 → 诊断 → 存储/报告/Dashboard。
7. **被问“你个人做了什么”**：按模块讲你负责的设计和实现；如果是独立项目，明确说独立完成；如果是团队，讲清边界。
8. **被问“为什么不用现成框架”**：先肯定现成工具，再说 Assay 补的是发布决策和领域解耦，不是重复造观测平台。

---

## 八、22 题深答（面试前重点）

> 第五部分是 30 题速查；这一部分把最容易被追问的 22 题展开。两处题目有重叠，时间紧时直接看这一部分。

### 1. 为什么做 Assay？和 LangSmith / Phoenix / Ragas 有什么区别？

**考察点**：你是不是在重复造轮子，是否理解评测和可观测性的边界。

**回答**：

观测平台解决的是“看得到”：Trace 存储、检索、可视化、实验管理；指标库解决的是“打得分”：单次回答的相关性、忠实度、上下文质量。Assay 解决的是第三个问题：**改完之后能不能发、退化在哪一步**。

所以它消费 Trace，而不是替代 Trace 平台。核心链路是：适配器归一化 Trace → 四层评测 → baseline/candidate 三数据集对比 → 发布决策 → 首错诊断 → 人工最终结论。它补的是版本对比、硬/软门禁、诊断和人工审核的闭环。

差异点有三个：一是领域解耦，`ProjectAdapter` 四能力契约不绑定 LangChain、OTel 或某个厂商；二是发布导向，accept/reject/rollback 和退出码是给 CI 用的；三是可追溯，诊断复用已经评测过的 Trace，不重跑 Agent。

**可追问**：那为什么不直接用 LangSmith 的 experiment？答：可以接它的 Trace，但发布策略、关键用例、人工审核和领域规则需要可定制；Assay 可以把 LangSmith/Phoenix 当作 Trace 来源。

### 2. 为什么用 Java，不用 Python？零运行时依赖的代价是什么？

**考察点**：技术选型是否有理由，是否知道代价。

**回答**：

这个项目的目标用户是 Java 团队，Agent 可能通过 HTTP、SDK、子进程接入，评测框架本身要能打成单个 jar、在 CI 里直接跑。Java 17 的 record、强类型、并发工具和 JUnit 很适合做契约、存储和并发控制；零运行时依赖则让部署和离线运行更简单。

代价也很明确：JSON 编解码、文件存储、HTTP Server、路径校验都要自己写，代码量和测试量更大；没有 YAML、没有数据库、没有现成的 Web 框架。我的判断是，这个项目的核心价值在“可移植 + 可审计”，所以接受这些代价；如果团队已经有 Python 评测栈，这套架构也完全可以换语言实现，契约和决策逻辑是语言无关的。

**可追问**：为什么不用 YAML？答：核心零依赖，JSON 已经够用，而且严格校验更容易做；YAML 的隐式类型和解析器依赖会引入额外风险。

### 3. 这个项目最难的地方是什么？最有价值的点是什么？

**考察点**：你是否只会写功能，还是理解系统性问题。

**回答**：

最难的不是规则引擎，而是**让决策可复现、可追溯、不混数据**。具体包括：baseline 和 candidate 必须可比（版本身份、数据集、配置一致）；缺失指标不能变成 0；异常退出后要能恢复且不能把两次实验混在一起；通过率不变时还要能发现用例一进一出；诊断必须复用同一批 Trace，不能重跑。

最有价值的点是把“测试通过”推进到“可以发布/不可以发布，以及为什么”。报告里有硬失败、软告警、场景分布、首错步骤和证据路径，人工结论单独存储，不覆盖自动报告。这个“证据 + 判定 + 人的决定权”的组合，是它和普通评测脚本最大的区别。

**可追问**：最难的 bug？见第 22 题。

### 4. 为什么要 `ProjectAdapter` 四能力？怎么避免绑定某个 Agent 框架？

**考察点**：领域解耦是否真的落地。

**回答**：

四能力是把领域项目必须回答的问题固定下来：怎么调用 Agent（`callAgent`）、怎么把原生 Trace 转成标准契约（`readTrace`）、哪些规则是硬门禁（`hardGates`）、哪些只是软质量（`softQuality`）。框架核心只依赖 `NormalizedTrace` 和 `Rule`，不关心底层是 HTTP、Java SDK、子进程还是离线文件。

避免绑定的关键有两点：一是标准 Trace 是稳定契约，领域字段放 `fields`，框架只读路径；二是适配器可以独立打包，通过 `META-INF/services` 或类名加载，框架和领域项目可以分开发布。HTTP 和 `trace_file` 配置则让不写 Java 的用户也能接入。

**可追问**：适配器很重怎么办？答：领域侧只做归一化和规则声明，评测、存储、报告、Dashboard 全部复用；适配器 jar 会被整体读入内存，不长期占用文件句柄，Windows 上可以直接替换。

### 5. `NormalizedTrace` 为什么这样设计？哪些字段是必须的？

**考察点**：数据模型是否支撑评测、诊断和版本对比。

**回答**：

`trace_id` 和 `final_output` 是完整性硬条件，缺失就直接硬失败；`events` 给行为层和首错诊断；`fields` 给领域规则；`feedback` 放显式负反馈、重复提问、改写问题等弱信号；`target_type/id/version` 用于演化时校验版本身份；`raw` 保留原始证据，方便排查。

不直接拿原始 JSON 做规则，是因为不同 Agent 的字段命名和嵌套完全不同，评测层需要稳定的路径和统一语义。领域项目在 `readTrace` 里做一次归一化，后面所有规则、报告、诊断都复用这个契约。

**可追问**：没有 Trace 怎么办？答：结构层直接硬失败，不会静默跳过；外部轨迹缺少某个 case 也是硬失败。

### 6. 四层评测怎么分工？为什么一致性和反馈不直接判失败？

**考察点**：是否理解确定性评测和非确定性评测的边界。

**回答**：

结构层看 Trace 完整性、硬门禁和软质量；行为层看步数、重试、延迟、重复步骤、模块错误；一致性层对高价值节点做多次运行，比较结果是否稳定；反馈层把显式负反馈、重复提问、改写问题变成人工复核候选。

一致性和反馈不直接判失败，是因为它们本身就是不确定信号：模型有随机性，用户反馈也可能有噪声。直接把差异判成 badcase，会制造大量误报；更合理的做法是进入 review queue，由人确认后再晋级到 regression/improvement/holdout。

**可追问**：那硬门禁会不会太松？答：硬门禁只放确定性、可复现的规则；质量问题用软告警和人工复核兜底。

### 7. 硬门禁和软告警的边界？为什么 LLM 不能参与硬门禁？

**考察点**：发布判定的可靠性意识。

**回答**：

硬门禁是“不通过就不能发布”的确定性规则，比如 Trace 完整性、关键路由、必要字段、安全规则；软告警是“值得人看”的质量信号，比如回答长度、风格、语义建议。硬门禁必须可复现、可审计、抗 prompt injection，LLM 做不到稳定，所以只做语义分析、疑似模块、修改建议和 few-shot 候选，报告里明确标注非权威。

这也是一个产品原则：LLM 可以加速分析，但不能替代发布责任。即使以后接 LLM-as-judge，也应该先做校准、只进软质量或人工复核，不直接进硬门禁。

**可追问**：LLM 分析失败怎么办？答：失败不影响评测事实，报告里记录错误，硬门禁照常运行。

### 8. 规则为什么用“路径 + 操作符”？工具链、参数这种复杂逻辑怎么表达？

**考察点**：规则引擎的扩展性和边界。

**回答**：

规则是数据，才能序列化、审计、给 HTTP 用户零代码配置。`actual` 从 Trace 读路径，`expected` 从用例的 `expected` 读路径，或者用固定 `value`；操作符支持 `eq/ne/contains/not_contains/min/max/in/exists`。这让路由、字段、答案包含、长度、延迟这类判断都能声明。

工具链和参数这种复杂逻辑，推荐让适配器在 `readTrace` 里暴露成结构化字段，比如 `fields.tool_chain`、`fields.tool_arguments.query_order.order_id`，再用普通规则比较。这样框架不需要引入脚本引擎，也不会把领域逻辑写死。

**可追问**：为什么不做 `sequence_eq` 之类的算子？答：可以加，但会增加规则引擎复杂度；当前通过适配器归一化已经能表达，优先保持核心简单。

### 9. 缺失指标为什么保留 `null`，不补 0？覆盖率怎么用？

**考察点**：数据完整性意识。

**回答**：

缺失不是零。补 0 会把“没测到”伪装成“表现很好”，比如没有 Token 数据却显示成本为 0、没有 Trace 却显示延迟为 0。Assay 保留 `null`，同时记录 `metric_coverage` / `objective_coverage` 的 `sample_count`、`missing_count`、`case_count`；必需目标覆盖不全直接 rollback，显式测量到的 0 仍然是 0。

测试里专门区分了“缺失”和“真实零值”，也覆盖了聚合溢出：非有限结果视为缺失，不产生 Infinity。这样报告不会给出虚假的精确值。

**可追问**：那缺失太多怎么办？答：先修适配器或数据采集；如果业务允许缺失，把目标设为非必需，但报告仍展示覆盖率。

### 10. accept / reject / rollback 的判定逻辑？阈值怎么定？

**考察点**：发布决策是否清晰、可解释。

**回答**：

`rollback` 是“破坏了不能坏的”：regression/holdout 硬失败、通过率下降、场景门禁/质量门禁不通过、版本身份不匹配、必需指标缺失。`reject` 是“没证明变好”：improvement 没有达到最低改善。`accept` 是“变好且保护集没超退化”。所有理由写入 `reasons`，报告和 Dashboard 可追溯。

阈值不是拍脑袋：先看 baseline 和业务风险，关键场景从严格开始（比如关键用例 0 回归、通过率不低于 95%），用 holdout 验证，再根据历史波动调整。阈值应该版本化、可 review，不能拿 holdout 反复调参。

**可追问**：为什么退出码有 2？答：`0` 正常，`1` 硬门禁失败或决策为负，`2` 用法错误或需要人工复核；需要人看的运行不能看起来像通过。

### 11. 为什么需要 improvement / regression / holdout 三个集？怎么防 holdout 污染？

**考察点**：是否理解评测集设计和过拟合。

**回答**：

improvement 证明候选声称要解决的问题；regression 防止已经修好的能力复发；holdout 是候选生成过程没见过的留出样本，防止只对生成候选的样本有效。baseline 和 candidate 必须跑同一套用例，并校验 target 身份/版本。

防污染的做法：holdout 不参与候选生成和调参；候选生成器只能看 improvement/regression；定期轮换或补充 holdout；报告按场景切片，避免只看总通过率；数据集内容哈希进入 run manifest，内容变了就拒绝复用。

**可追问**：holdout 也会被间接污染怎么办？答：至少保证流程隔离，并在报告里标注数据集版本和来源；更高要求是独立团队维护 holdout。

### 12. scenario gates、quality gates、objectives 的区别？怎么防隐藏回归？

**考察点**：门禁模型是否清晰，是否考虑小样本和通过率陷阱。

**回答**：

scenario gate 看切片：某个场景的最低样本数、最低通过率、允许退化；quality gate 看候选自身是否可接受：按角色设最低硬通过率、最大回归条数、最大关键回归数、最小样本；objective 看相对基线的数值变化：mean/sum/min/max/p50/p95/p99、最小改善、最大退化、绝对上限。

隐藏回归靠逐用例状态迁移 + 条数门禁，而不是只看聚合通过率。`case_transitions` 把用例分成稳定通过、回归、恢复、持续失败；`maximum_task_regressions` 和 `maximum_critical_task_regressions` 按条数拦。默认 `require_regression_pass` 能兜住 regression 集的硬失败，但在 holdout/improvement 或放宽策略时，条数门禁更明确。

**可追问**：关键用例怎么标？答：用例 `metadata.critical = true`，回归清单会单独标记。

### 13. P95 怎么算？小样本怎么办？

**考察点**：指标计算的严谨性。

**回答**：

P95 用排序后的线性插值（R-7），不是简单取第 95% 个；报告同时展示 `样本数/用例数`，避免把 1/1 读成 100% 的精确能力。quality gate 有 `minimum_samples`，样本不够时直接判“证据不足”，不会给出自信的通过结论；场景通过率用 Wilson 区间，小样本区间更宽。

均值会掩盖长尾，P95 更适合延迟、成本这类指标；但 P95 本身也需要样本量支撑，所以覆盖率和最小样本数必须一起看。

**可追问**：为什么不用 P99？答：支持 p99，但小样本下更不稳定；默认看 P95，按业务需要配置。

### 14. `TraceDiff` 怎么对齐？为什么不用逐位置比较？

**考察点**：算法选择和可解释性。

**回答**：

按 `module/action` 做 LCS，先找最长公共子序列作为锚点，再处理未匹配区间：长度相同的配对判断替换/重排，剩余的是新增或缺失；匹配上的步骤比较 error、status、fields；所有差异按候选步骤排序，取第一处。超过 300 步退化为按位置比较，并在 `alignment` 里标注。

不用逐位置比较，是因为前面插入一步会让后面全部错位，首错会误报在第一步；也不用编辑距离，因为要定义替换代价，解释成本更高。LCS 足够、可实现、结果可解释。

**可追问**：为什么只报第一处？答：第一处可见差异对定位最有价值；后续差异也会保存在 `divergences` 里，供深入排查。

### 15. 为什么忽略 span_id、时间字段？非确定性轨迹怎么办？准确性怎么保证？

**考察点**：是否理解“诊断是辅助证据，不是硬门禁”。

**回答**：

`span_id`、`parent_span_id`、时间/耗时字段每次运行都会变，不代表行为变化；默认忽略，用例可以用 `metadata.trace_diff_ignore` 追加。耗时问题由指标和门禁负责，不靠轨迹 diff。

非确定性轨迹可能合法地走不同路径，所以诊断只进入报告和人工复核，不直接改变发布判定。准确性靠确定性类别、字段路径、测试和故意退化夹具保证；它是“第一处可见差异”，不是因果根因，不造“置信度”这种不可验证的数字。

**可追问**：漏报怎么办？答：诊断是辅助；硬门禁仍由规则和指标保证。误报/漏报通过人工复核和补充规则迭代。

### 16. 为什么诊断不重跑 Agent？轨迹完全一致但用例失败，怎么解释？

**考察点**：证据一致性。

**回答**：

重跑对非确定性/线上 Agent 不成立，也可能产生和原报告不一致的轨迹；Assay 的 `case_transitions` 直接读 baseline/candidate 的 run 结果，证据和报告是同一批。

如果轨迹完全一致但用例失败，归为 `no_visible_divergence`：可能规则检查的字段不在 steps/final_output 里，可能重复 trace id，可能 Trace 缺失，可能适配器执行错误。报告保留失败规则和 Trace 证据，提示人工复核，而不是硬编一个原因。

**可追问**：重复 trace id 怎么处理？答：落为硬失败，并且不重复记数。

### 17. 外部轨迹怎么做到零代码？OpenInference / OTLP 怎么兼容？怎么保证可比性？

**考察点**：接入能力和工程细节。

**回答**：

`trace_file` 适配器读 JSON，按 `case_id` 关联用例，不需要写 Java。normalized 格式就是 `NormalizedTrace.toJson()`；OpenInference 读 `openinference.span.kind`、`tool.name`、`input.value`、`output.value`、token 等语义属性；OTLP 读 `resourceSpans/scopeSpans` 和 `{key, value:{stringValue}}` 属性数组。

可比性靠四点：同一套用例、同一套规则、target 身份/版本校验、重复 case 拒绝；用例在导出里缺失时硬失败，不静默跳过。`target_type/id/version` 可以覆盖文件里的目标身份，保证 evolve 的版本匹配。

**可追问**：流式/SSE 支持吗？答：当前只支持同步 JSON；流式需要另做采集和聚合，不在核心范围。

### 18. 断点恢复怎么保证不混数据？并发模型是什么？为什么用文件锁？

**考察点**：工程可靠性。

**回答**：

每次 run 有稳定 identity，每个 case 计算内容哈希；恢复时数据集、版本身份或执行配置任一不一致都拒绝复用，能区分“新增 case”和“内容被改过”。结果按最多 32 条批次落盘，未提交批次在恢复时重跑；只有最后一行未写完才允许截断，中间损坏明确报错。

并发用有界线程池，`maxInFlight = 2 × workers`，避免慢 case 饿死队列；适配器可以声明 `maxConcurrency`；写入用原子文件操作。同一 run 用文件锁保证只有一个持有者，第二个 JVM 会立即失败而不是排队。

用文件锁而不是数据库，是为了零依赖、单机可移植；代价是没有分布式协调。存储层只有 `Results` 一个接口，想换 JDBC/SQLite/对象存储，另写实现即可，引擎不用改。

**可追问**：半行 UTF-8 怎么恢复？答：只截断最后一行未完成记录，完整但缺换行补分隔符，UTF-8 半字符可恢复；中间损坏不猜。

### 19. evolve-auto 怎么防路径逃逸、保护基线？进程/容器怎么隔离？Dashboard 安全怎么保证？

**考察点**：安全边界和信任等级。

**回答**：

路径必须是 `/` 分隔的相对路径，拒绝盘符、冒号、控制字符、尾随空格/点和符号链接；多文件候选先在 `.assay-staging` 暂存区完成复制、修改、哈希和 manifest，再整体移动；已提交候选校验哈希；回滚不会覆盖基线目录。

进程 Runner 超时终止整棵进程树，单流默认最多 1 MiB，超限立即终止；容器 Runner 默认禁网、只读挂载候选目录、只读根文件系统、移除 capabilities、限制 CPU/内存/PID。按信任等级选择。

Dashboard 是只读的：artifact id 是受限路径的 base64，服务端校验仍在报告根目录内、拒绝符号链接、限制 64 MiB；审计补丁读取时核对 `diff_sha256`，不一致拒绝展示；没有写接口，只监听本机。

**可追问**：为什么不用 React？答：零构建、零依赖、Java 直接托管；纯函数用 Node 测试，页面规模不需要组件框架。

### 20. 211 项测试怎么组织？最有价值的是哪些？评测集质量怎么保证？

**考察点**：测试是否只覆盖 happy path。

**回答**：

测试按失败模式组织：评测闭环、四层规则、运行完整性/恢复、并发（真的启动第二个 JVM 抢锁）、演化决策、自动演化预算/检查点、进程树/容器安全参数、测试集选择、范围审计、OpenAI Trace、外部轨迹、JDBC 存储、监听器/失败回调、Dashboard。最有价值的是恢复、并发、进程树、路径安全、JDBC 契约和诊断边界，不是“跑通一个 case”。Starter 另有 4 项上下文测试，Micrometer 2 项（成功/失败 run），OTel 2 项（父子 span/失败关闭）。

评测集质量靠：领域 review、预期值明确、ID 唯一、场景覆盖、关键用例标记、数据集版本/内容哈希、线上 badcase 回流。`review` 保存人工结论，`export` 导出 regression/few-shot 候选，`promote-review` 晋级到 improvement/regression/holdout，形成闭环。

**可追问**：怎么评估评测器本身？答：`EvaluatorMetrics` 有 agreement、false positive/negative、field accuracy、repeat stability；人工 adjudication 做校准。

### 21. 项目有什么不足？数据量大了怎么办？非确定性 Agent 还有意义吗？

**考察点**：是否知道边界，是否有工程判断。

**回答**：

不足是默认仍是单机文件存储（JDBC 已有，但缺 schema 迁移和分布式锁）、没有多租户/权限、没有生产流量验证、诊断是辅助证据、没有统计显著性检验。数据量大了，用 JDBC/对象存储替换文件存储，Trace 接入做流式处理，`TraceDiff` 有 300 步上限，结果分页，评测 worker 独立部署，锁换成数据库/分布式锁。

非确定性 Agent 仍然可以评，但不能用一次运行下结论：高价值节点多次运行、一致性检查、置信区间、样本覆盖、场景门禁、人工复核；硬门禁只放在稳定信号上。非确定性不是不评测，而是要承认不确定性。

**可追问**：下一步最想做什么？答：JDBC schema 迁移与分布式锁、Trace 接入服务、趋势看板、校准过的 LLM judge（仍不进硬门禁）、PR 评论和发布流水线集成。

### 22. 最大的取舍是什么？如果重做会怎么改？讲一个最难的 bug。

**考察点**：反思能力和真实经历。

**回答**：

最大的取舍是零运行时依赖：换来了可移植、离线、无供应链负担，但也换来了更多自研代码和测试。如果重做，会更早定义 Trace schema/versioning，把决策引擎从 CLI 抽成纯函数，给 `Path`/`ValueOps` 加属性测试，并更早把 JDBC 做成一等存储、补上 schema 迁移和契约测试；但零依赖对本地演示和可移植性仍然有价值。

最难的 bug 是断点恢复：最后一行可能只写了一半，甚至 UTF-8 字符写到一半，简单按行解析会报错或丢数据。做法是只允许最后一行未完成时截断，完整但缺换行补分隔符，UTF-8 半字符恢复，中间损坏明确报错；测试覆盖截断尾部、半字符、缺换行和再次中断，恢复后不会把两次实验混在一起。

**可追问**：怎么验证？答：临时目录构造损坏文件，断言恢复后的 case 数、内容和身份清单。

---

## 九、快问快答 13 条

1. **为什么不用 YAML？** 核心零依赖，JSON 够用且严格校验更容易；YAML 的隐式类型和解析器依赖会引入风险。
2. **为什么退出码有 2？** `2` 表示用法错误或需要人工复核；需要人看的运行不能看起来像通过。
3. **重复 trace id 怎么处理？** 落为硬失败，并且不重复记数。
4. **适配器 jar 被占用怎么办？** 加载时整体读入内存，不长期持有文件句柄，Windows 上可直接替换。
5. **支持流式/SSE 吗？** 当前只支持同步 JSON；流式需要另做采集和聚合。
6. **超时和重试怎么做？** 单次调用有 timeout；重试是显式 `--retries`，可能重复调用有副作用的接口，默认不重试。
7. **怎么处理敏感信息？** 报告本地保存，配置里不写密钥、用环境变量；但这不是完整的 DLP，生产要接脱敏和权限。
8. **支持多轮会话吗？** Trace 的 events 可以表达多步，但没有 session store；多轮需要适配器提供完整会话轨迹。
9. **怎么评估评测器本身？** agreement、FP/FN、field accuracy、repeat stability + 人工校准。
10. **为什么默认文件存储，JDBC 怎么选？** 默认文件是为了零依赖、单机可移植；JDBC 已实现，`--store jdbc:...` 切换，driver 由使用方提供。
11. **JDBC 存储怎么保证和文件存储同语义？** 同一套 `Results` 契约测试：manifest 校验、批量事务、`SELECT FOR UPDATE` 追加检查、run 锁。
12. **Spring Boot Starter 会不会污染核心？** 不会；Starter 是独立扩展，核心不反向依赖 Spring，且没有 `ProjectAdapter` 就不装配 Engine。
13. **为什么用 EvaluationListener，而不是在引擎里直接打点？** 核心保持零依赖；Micrometer/OTel 放扩展，默认 no-op，listener 抛异常也不会改变评测结果。

---

## 十、七个深挖专题：TraceDiff / 门禁 / 恢复 / JDBC / Starter / Micrometer / OTel

> 这一部分用于应对“再往下讲一点”的追问。建议按“输入输出 → 算法 → 边界 → 测试 → 取舍”的顺序讲。

### 10.1 TraceDiff：从 LCS 到首错证据

#### 10.1.1 输入与输出

输入是两份 JSON 形态的 Trace（`NormalizedTrace.toJson()`），每份包含：

```json
{
  "trace_id": "trace-1",
  "final_output": {"route": "SUPPORT"},
  "events": [
    {"module": "IntentRouter", "action": "plan", "status": "ok", "duration_ms": 1, "error": null, "fields": {}},
    {"module": "IntentRouter", "action": "route", "status": "ok", "duration_ms": 2, "error": null, "fields": {"route": "SUPPORT"}}
  ],
  "fields": {"route": "SUPPORT"}
}
```

输出是 JSON 形态的诊断：

```json
{
  "alignment": "lcs",
  "baseline_steps": 2,
  "candidate_steps": 2,
  "divergence_count": 1,
  "first_divergence": {
    "category": "step_fields",
    "step": 2,
    "baseline_step": 2,
    "candidate_step": 2,
    "reason": "candidate step 2 IntentRouter/route fields.route changed",
    "changed_paths": ["route"],
    "baseline_values": ["SUPPORT"],
    "candidate_values": ["GENERAL"],
    "baseline": {"module": "IntentRouter", "action": "route", "status": "ok", "fields": {"route": "SUPPORT"}},
    "candidate": {"module": "IntentRouter", "action": "route", "status": "ok", "fields": {"route": "GENERAL"}}
  },
  "divergences": [],
  "divergences_truncated": false
}
```

注意：`first_divergence` 保留完整事件字段，后续 `divergences` 只保留摘要，避免 `evolution.json` 过大。

#### 10.1.2 算法步骤

1. **抽取步骤**：把每个事件转成 `Step(number, module, action, key)`，`key = module + "\0" + action`。
2. **LCS 对齐**：对两个 `key` 序列做最长公共子序列 DP；回溯得到匹配锚点。匹配优先，无法匹配时按 `dp[i+1][j] >= dp[i][j+1]` 跳过基线步骤，保证确定性。
3. **处理未匹配区间**：两个锚点之间的缺口，先按位置配对，判定 `step_changed` / `step_reordered`；剩余基线步骤是 `step_removed`，剩余候选步骤是 `step_added`。
4. **比较匹配步骤**：顺序是 `key → error → status → fields`。同一步骤的 error 变化优先于 status，status 变化优先于字段变化。
5. **字段 diff**：对 `fields` 做递归比较，map 按 key 并集、list 按下标、标量用 `ValueOps.equal`；记录叶子路径、baseline/candidate 值和截断标记。
6. **收尾**：如果没有任何步骤差异，再比较 `final_output`，最后比较 trace 级 `fields`；都没有差异就是 `no_visible_divergence`。
7. **排序**：按候选步骤号、再按基线步骤号排序，取第一条作为 `first_divergence`。
8. **上限**：超过 300 步退化为按位置比较，`alignment` 标为 `positional`；后续差异最多保留 20 条，`divergences_truncated` 标出截断。

#### 10.1.3 差异分类表

| category | 含义 | 步骤语义 |
|---|---|---|
| `step_added` | 候选多了一步 | 候选步骤号 |
| `step_removed` | 候选少了一步 | 缺失位置的候选步骤号 |
| `step_changed` | 同位置步骤换成了另一个 module/action | 候选步骤号 |
| `step_reordered` | 同一步骤换了位置 | 候选步骤号 |
| `step_fields` | 同一步骤的 `fields` 变化 | 候选步骤号 |
| `step_error` | 同一步骤的 error 变化 | 候选步骤号 |
| `step_status` | 同一步骤的 status 变化 | 候选步骤号 |
| `final_output` | 步骤一致，最终输出变化 | 无 |
| `trace_fields` | 步骤和输出一致，Trace 级字段变化 | 无 |
| `missing_trace` | 一侧没有可读 Trace | 无 |
| `no_visible_divergence` | 都一致，失败来自规则或运行完整性 | 无 |

#### 10.1.4 为什么用 LCS，而不是逐位置或编辑距离

- **逐位置**：前面插入一步会让后面全部错位，首错会误报在第一步。
- **编辑距离**：需要定义替换代价，解释成本高；LCS 的“匹配锚点 + 缺口处理”更直观。
- **LCS 的代价**：最坏 O(n×m) 时间和内存，所以有 300 步上限；超过上限退化，保证不会因为异常大 Trace 拖垮评测。
- **确定性**：同样的输入一定得到同样的对齐和排序，便于复现和测试。

#### 10.1.5 忽略字段与噪声控制

默认忽略：

- `span_id`、`parent_span_id`
- `start_time`、`end_time`、`start_time_ms`、`end_time_ms`
- `timestamp`、`duration_ms`、`latency_ms`

原因：这些字段每次运行都会变，不代表行为变化。耗时问题由指标和门禁负责，不由 TraceDiff 负责。

用例可以通过 `metadata.trace_diff_ignore` 追加路径，例如：

```json
{"trace_diff_ignore": ["llm.response_id", "http.request_id"]}
```

路径匹配规则是“精确命中或以 `entry + "."` 开头”，所以忽略 `llm.response_id` 不会误伤 `llm.model`。

#### 10.1.6 边界案例

| 场景 | 行为 |
|---|---|
| 基线/候选都没有 Trace | `missing_trace`，原因写“双方都没有可读 Trace” |
| 只有候选缺少 Trace | `missing_trace`，候选侧缺失 |
| 候选多一步 | `step_added`，步骤号是候选位置 |
| 候选少一步 | `step_removed`，步骤号是缺失位置的候选步骤号 |
| 同一步骤参数变化 | `step_fields`，`changed_paths` 精确到叶子 |
| 同一步骤报错 | `step_error`，优先于字段差异 |
| 步骤全一致、答案变化 | `final_output` |
| 步骤全一致、答案和 Trace 字段都一致 | `no_visible_divergence` |
| 超过 300 步 | `alignment = positional`，可能把插入后的步骤都标成变化 |
| 字段 diff 超过 12 条 | `changed_paths_truncated = true`，只保留前 12 条 |

#### 10.1.7 测试与验证

`TraceDiffTest` 覆盖：

- 插入一步不级联
- 同一步骤换工具
- 参数字段变化
- 默认忽略 `span_id`，自定义忽略 `response_id`
- 最终输出变化
- Trace 字段变化
- 删除步骤的候选位置
- 缺失 Trace
- 同一步骤 error 优先于 fields
- 完全一致时的 `no_visible_divergence`

`EvolutionTest` 再用故意退化的夹具验证端到端：`rollback`、`case_transitions.regressed`、`first_divergence.category = step_fields`、`step = 2`、`changed_paths = ["route"]`。

#### 10.1.8 面试追问

- **“怎么保证第一处是真的第一处？”** 按候选步骤号排序；LCS 锚点保证对齐；同一步骤内按 error → status → fields 的固定优先级。
- **“如果两个步骤一模一样，LCS 怎么处理？”** 相同 key 会匹配；重复步骤可能导致对齐歧义，但排序和 tie-break 是确定性的，极端情况下退化为位置比较。
- **“为什么只报第一处？”** 第一处对定位最有价值；后续差异保存在 `divergences`，只是不展开完整字段。
- **“准确率是多少？”** 不造准确率数字；用确定性类别、字段路径、边界测试和人工复核保证可解释性。它是“第一处可见差异”，不是因果根因。
- **“怎么扩展？”** 可以增加可配置的 step key、语义 diff、跨多条 Trace 的聚类，或把 `TraceDiff` 升级成规则的一部分；但当前优先保持辅助证据定位。

---

### 10.2 门禁设计：从规则到发布决策

#### 10.2.1 门禁模型的分层

| 层级 | 作用 | 例子 |
|---|---|---|
| 用例级硬门禁 | 单个用例必须通过 | Trace 完整、关键路由、安全规则 |
| 用例级软质量 | 产生告警/复核候选 | 回答长度、风格、疑似模块 |
| 场景门禁 | 防止小切片被平均值掩盖 | 高风险场景最低通过率、最大跌幅 |
| 绝对质量门禁 | 候选自身是否可接受 | 最低硬通过率、最大回归数、最大关键回归、最小样本 |
| 数值目标 | 相对基线的改善/退化 | latency、tokens、cost、业务字段 |
| 演化决策 | 汇总成 accept/reject/rollback | 版本发布结论 |

关键原则：**硬门禁是确定性的；LLM 只做建议；任何 rollback 都优先于 reject/accept。**

#### 10.2.2 决策顺序

`EvolutionEngine.decide` 的实际顺序：

1. 版本身份/目标不匹配 → rollback。
2. regression 集：候选硬失败（如果 `require_regression_pass`）、通过率下降、软告警增加 → rollback。
3. holdout 集：候选硬失败（如果 `require_holdout_pass`）、通过率跌幅超限、软告警增加 → rollback。
4. scenario gates：场景缺失、样本不足、通过率低于下限、跌幅超限、软告警增加 → rollback。
5. quality gates：样本不足、绝对通过率低于下限、回归条数/关键回归条数超限 → rollback。
6. objectives：必需指标缺失、相对退化超限、绝对上限超限 → rollback；improvement 未达到最低改善 → reject。
7. 没有 rollback/reject，且满足改善要求（或策略不要求改善）→ accept；否则 reject。

这个顺序很重要：**rollback 是“不能发”，reject 是“没证明能发”**；两者都不等于通过。

#### 10.2.3 三类门禁的区别

**Scenario gate** 看切片：

```json
{
  "scenario": "high-risk",
  "roles": ["regression", "holdout"],
  "minimum_case_count": 5,
  "minimum_hard_pass_rate": 1.0,
  "maximum_hard_pass_rate_drop": 0,
  "maximum_soft_warning_increase": 0
}
```

适合“高风险场景不能有任何回归”，防止 1 条关键用例被 99 条普通用例平均掉。

**Quality gate** 看候选自身：

```json
{
  "roles": ["regression", "holdout"],
  "minimum_hard_pass_rate": 0.95,
  "maximum_task_regressions": 0,
  "maximum_critical_task_regressions": 0,
  "minimum_samples": 10
}
```

适合“候选必须达到绝对下限”，以及“通过率不变但用例一进一出”的隐藏回归。

**Objective** 看数值：

```json
{
  "name": "latency_p95",
  "metric": "latency_ms",
  "direction": "minimize",
  "aggregation": "p95",
  "minimum_improvement": 0,
  "maximum_regression": 50,
  "required": false,
  "maximum": 1200
}
```

`maximum` 是候选的绝对上限，和基线无关；`minimum_improvement` 只在 improvement 集用于 reject；`maximum_regression` 在所有角色用于 rollback。

#### 10.2.4 隐藏回归的完整例子

假设 baseline 和 candidate 都是 10 条用例、5 条通过：

- baseline 通过：A、B、C、D、E
- candidate 通过：A、B、C、D、F
- 通过率都是 50%，`hard_pass_rate_delta = 0`
- 但 `case_transitions.regressed = [E]`，`recovered = [F]`

如果只看通过率，会误判“没有退化”；`maximum_task_regressions = 0` 会直接 rollback。即使默认 `require_regression_pass` 已经能兜住 regression 集的硬失败，逐用例清单也能告诉人“退化的是 E，不是整体不行”。

#### 10.2.5 阈值怎么定

1. **从基线开始**：先跑 baseline，得到通过率、延迟、Token、成本分布。
2. **按业务风险分层**：关键场景/关键用例用绝对下限；普通场景允许小幅波动。
3. **用 holdout 验证**：阈值不能只在 improvement 上调；要看保护集。
4. **看样本量**：样本少时用 `minimum_samples` 和 Wilson 区间，不要用 1/1 下结论。
5. **版本化**：策略文件进 git，阈值变更要 review；不要拿 holdout 反复调参。
6. **定期复盘**：线上 badcase 回流后，更新 regression/improvement，再调整阈值。

#### 10.2.6 常见追问

- **“门禁会不会太严，导致什么都发不出去？”** 硬门禁只放确定性、高价值的规则；软质量走告警和人工复核；quality gate 有 `minimum_samples`，避免小样本误杀。
- **“多个门禁冲突怎么办？”** 任何一个 rollback 都优先；`reasons` 会列出所有违规项，报告里能看出是哪个角色、哪个规则。
- **“怎么避免为了过门禁而过拟合？”** holdout 不参与候选生成；threshold 版本化；场景切片；不要反复用 holdout 调参。
- **“LLM 能不能做门禁？”** 可以辅助分析，但不进硬门禁；最多做软质量或人工复核，并需要校准。
- **“怎么调试一个 rollback？”** 先看 `reasons`，再看 `comparisons.*.case_transitions` 的 regressed，最后看 `first_divergence` 和失败规则；报告和 Dashboard 是同一份数据。

---

### 10.3 恢复机制：从批次落盘到跨进程锁

#### 10.3.1 存储布局

```text
.assay/store/
├── runs/
│   └── <run-id>/
│       ├── run.json          # run 元数据、状态、配置
│       ├── manifest.json     # case_id -> 内容 SHA-256
│       └── cases.jsonl       # 追加写、后写覆盖
├── reviews/
│   └── <run-id>.jsonl        # 人工结论
├── evolutions/
│   └── <experiment-id>.json  # 演化审计记录
└── .locks/
    └── <run-id>.lock         # 一字节文件锁
```

#### 10.3.2 写入路径

1. `startRun`：新建或恢复 `run.json`；恢复时校验 adapter/suite/source/metadata（忽略 `case_count`）。
2. `saveCases`：把一批 `CaseResult` 归一化成 JSON，补上 `hard_pass` / `soft_warning_count`，一次性追加到 `cases.jsonl`。
3. `appendCases`：先修复上次中断的尾部，再追加；追加成功后更新内存缓存。
4. `finishRun`：读取所有 case，按 case id 排序重写 `cases.jsonl` 做压缩，再写最终状态。
5. `addCaseCheck`：对已经落盘的 case 追加一条检查，重新追加整条记录，后写覆盖。

批次大小由 `EvaluationEngine` 控制：`min(32, maxInFlight)`，即最多 32 条。异常退出最多丢失当前未提交批次，恢复时重跑。

#### 10.3.3 恢复校验

恢复不是“接着写”这么简单，要防止把两次实验混在一起：

1. **run 身份**：adapter、suite、source、metadata 必须一致；`case_count` 允许变化。
2. **case manifest**：每个 case 有内容 SHA-256；恢复时对比：
   - 旧 manifest 里有、新数据集没有 → `missing case_id`，拒绝；
   - 同 case 内容哈希不同 → `changed case_id`，拒绝；
   - 新数据集新增 case → 允许，合并进 manifest。
3. **legacy run**：没有 manifest 但有已保存 case → 拒绝恢复，避免无法判断内容是否变化。
4. **数据集/版本身份/执行配置**任一不一致 → 拒绝复用。

#### 10.3.4 尾部修复算法

`loadCases` 逐行读取 `cases.jsonl`，只允许**最后一行未完成**：

| 磁盘状态 | 行为 |
|---|---|
| 最后一行是完整 JSON + `\n` | 正常读取 |
| 最后一行是完整 JSON、缺 `\n` | 读取，并记录 `needsNewline=true`；下次追加前补 `\n` |
| 最后一行是半行 JSON | 解析失败，记录 `validLength=该行起点`；下次追加前截断 |
| 最后一行是半行 UTF-8 | 解码失败；同样只在未终止的最后一行容忍，截断 |
| 中间某行损坏 | 直接 `IllegalStateException`，不猜、不跳过 |
| 行里没有 `case.case_id` | 直接报错 |
| 重复 case | 后写覆盖（upsert 语义），读取时按 case id 排序 |

`appendCases` 在写入前用 `FileChannel.truncate(validLength)` 截断未完成尾部；如果 `needsNewline`，在内容前补 `\n`。追加失败时清空内存缓存，避免把未提交记录当成已提交。

#### 10.3.5 并发与锁

- 每个 run 一个 `.locks/<run-id>.lock`，用一字节 `FileChannel.tryLock`。
- 跨进程：第二个 JVM 拿不到锁，直接失败，不排队。
- 同 JVM：`OverlappingFileLockException` 也会被转成 `already running`。
- 锁的持有范围是整个 suite 执行；`try-with-resources` 关闭。
- 存储层只有 `Results` 接口；文件锁是当前实现的细节，换 JDBC 可以实现数据库锁。

#### 10.3.6 故障矩阵

| 故障点 | 结果 | 恢复 |
|---|---|---|
| 调用 Agent 后、写批次前崩溃 | 该批未提交 | resume 重跑该批 |
| 写 `cases.jsonl` 过程中崩溃 | 最后一行半行 | 下次启动截断尾部 |
| 写完一行但没写 `\n` | 完整行、缺换行 | 下次追加补 `\n` |
| UTF-8 字符写到一半 | 解码失败在最后一行 | 截断尾部 |
| 中间行损坏 | 明确报错 | 不自动修复，人工检查 |
| 两个进程同时跑同一 run | 第二个 `already running` | 等第一个结束或换 run id |
| resume 时 case 内容变了 | manifest 哈希不匹配 | 拒绝恢复，换新 run |
| 重复 trace id | 运行结束后加硬检查 | 报告里硬失败 |
| 适配器 jar 被替换 | 加载时整体读入内存 | 不会长期占用文件句柄 |

#### 10.3.7 语义：at-least-once，不是 exactly-once

这点面试时一定要主动说清楚：

- 已提交的批次不会丢；未提交的批次会在 resume 时重跑。
- 因此 Agent 调用可能重复，框架不保证 exactly-once；有副作用的接口需要幂等，或者用稳定的 `run_id`/`case_id` 做去重。
- 批次越大，崩溃时重跑越多；批次越小，写入越频繁。当前上限 32 条是折中。

#### 10.3.8 测试

- `StoreRecoveryTest`：截断尾部、UTF-8 半字符、缺换行、再次中断、中间损坏、失败追加不产生虚假结果。
- `RunIntegrityTest`：空测试集/重复 case id 在建 run 前拒绝；数据集/版本身份/配置变化拒绝复用；批次重放；trace id 重复。
- `ConcurrencySafetyTest`：真的启动第二个 JVM 抢同一把锁；两个进程并发写不同 run 不丢数据。

#### 10.3.9 面试追问

- **“为什么不用数据库事务？”** 单机零依赖；文件锁和尾部修复覆盖当前场景。要上团队服务，换 `Results` 的 JDBC 实现和数据库锁。
- **“append 是原子的吗？”** 不是；所以需要尾部修复。`run.json`、`manifest.json` 这种小文件用临时文件 + 原子 move，避免半写。
- **“怎么保证不重复？”** 不保证 exactly-once；保证已提交批次不丢、未提交批次可重跑，副作用幂等由适配器负责。
- **“为什么按 case id 排序？”** 读取确定性，报告和测试稳定；写入仍是追加 + last-write-wins。
- **“如果文件很大怎么办？”** 当前是本地单机；可以换数据库/对象存储，或按 run 分片、流式读取。

---

### 10.4 JDBC 存储：把 `Results` 变成真正的存储契约

#### 10.4.1 为什么不是“加一个 DAO”

原来的 `Results.lockRun` 返回 `ResultStore.Handle`，接口和文件锁实现耦合；JDBC 存储不能返回文件锁。所以先把锁抽象成 `Results.RunLock`，再实现 `JdbcResultStore`。这是接口层的修正，不是简单加一张表。

#### 10.4.2 表结构

- `assay_run`：run 元数据、状态、时间、metadata JSON。
- `assay_case`：`(run_id, case_id)` 主键，payload JSON、hard_pass、soft_warning_count。
- `assay_case_manifest`：case 内容 SHA-256，用于恢复校验。
- `assay_review`：人工结论，UUID 主键。
- `assay_evolution`：演化结果，experiment_id 主键。
- `assay_lock`：run 级互斥，`run_id` 主键。

#### 10.4.3 语义

- `saveCases` 在一个事务里批量 update/insert，失败整体回滚。
- `startRun(resume=true)` 校验 adapter/suite/source/metadata，再校验 manifest 的 missing/changed；允许新增 case。
- `addCaseCheck` 用 `SELECT ... FOR UPDATE` 读改写，避免并发覆盖。
- `lockRun` 插入 `assay_lock` 行；重复主键 → `already running`；关闭时删除。
- 进程崩溃会留下锁行，需要人工清理；没有自动 TTL，这是已知边界。

#### 10.4.4 取舍

- 驱动不在框架 jar 内：核心仍零运行时依赖，使用方自己提供 JDBC driver。
- schema 用 `create table if not exists` 自动创建，没有迁移框架；生产应自己管迁移。
- `text`/`boolean`/`timestamp` 是便携子集；MySQL 大字段、Postgres 类型、锁超时要按库验证。
- 没有分布式锁 TTL、没有多租户、没有分页查询；这些是下一步。

#### 10.4.5 测试

- 5 项 `JdbcResultStoreTest`：保存/读取、resume/manifest、锁、审核/演化、追加检查。
- 1 项 CLI JDBC 测试：`--store jdbc:h2:...` 跑通完整 `run`。
- 实际用 H2 driver 做过 CLI 冒烟。

#### 10.4.6 追问

- **“为什么不用 JPA？”** 核心零依赖，JDBC 足够；JPA 会引入实体/事务/懒加载复杂度，而且这里的数据是 JSON 文档 + 少量索引列。
- **“事务边界在哪？”** 一个批次一个事务；`startRun`、`addCaseCheck`、`saveEvolution` 各自一个事务。
- **“怎么保证恢复不混数据？”** 和文件实现同一套 manifest 校验；缺失/变更/legacy run 都拒绝。
- **“锁崩溃了怎么办？”** 当前人工清理 `assay_lock`；下一步加 TTL/owner/heartbeat。

---

### 10.5 Spring Boot Starter：集成而不污染核心

#### 10.5.1 定位

`extensions/assay-spring-boot-starter` 是可选模块，不改变核心零依赖。它只做三件事：

1. 根据 `assay.store.type` 装配 `ResultStore` 或 `JdbcResultStore`。
2. 在存在 `ProjectAdapter` bean 时装配 `EvaluationEngine`。
3. 提供 `assay.workers`、`assay.timeout-seconds`、`assay.retries` 等配置。

#### 10.5.2 条件装配

- `@ConditionalOnProperty` 区分 file/JDBC。
- `@ConditionalOnBean(ProjectAdapter.class)`：没有适配器就不创建 Engine，避免框架猜怎么调 Agent。
- `@ConditionalOnMissingBean`：用户可覆盖任何 bean。
- `@AutoConfiguration` + `AutoConfiguration.imports`，不依赖组件扫描。

#### 10.5.3 测试

- 文件存储 + 有 Adapter → `ResultStore` + `EvaluationEngine`。
- JDBC + DataSource → `JdbcResultStore`。
- 没有 Adapter → 只有 `Results`，没有 `EvaluationEngine`。

#### 10.5.4 追问

- **“Starter 会不会把 Spring 带进核心？”** 不会；Starter 依赖 `assay`，反向不成立。
- **“为什么不自动装配 Dashboard？”** Dashboard 是可选运行时，涉及端口/生命周期/安全；当前让用户显式启动，避免隐式绑定 8080。
- **“JDBC 配置怎么给？”** Starter 只消费 `DataSource`，不猜 URL/用户名/密码；连接池和迁移由 Spring 应用负责。
- **“怎么发布？”** 已进入 Maven reactor，根目录 `mvn test` 会构建主项目、Starter 和 Micrometer；发布到 Maven Central 仍需额外的发布插件/签名配置。

---

### 10.6 EvaluationListener + Micrometer：可观测但不污染核心

#### 10.6.1 为什么是 SPI

核心只定义生命周期回调：`onRunStarted`、`onCaseStarted`、`onCaseFinished`、`onBatchSaved`、`onRunFinished`。默认 `NOOP`；`EvaluationEngine` 会吞掉 listener 异常，观测者不能改变评测结果。

这比在引擎里直接依赖 Micrometer 好：核心零依赖，测试可以用一个记录事件的 listener，Micrometer/OTel 只是实现。

#### 10.6.2 线程模型

- `onCaseStarted` / `onCaseFinished` 在 worker 线程上调用，可能并发。
- Micrometer 实现用 `ThreadLocal<Long>` 记录 case 开始时间，不共享可变计时器。
- `onRunStarted` / `onRunFinished` 在主线程；run 开始时间用 `ConcurrentHashMap` 保存，run_id 只做本地 key，不进标签。
- listener 必须线程安全、不能抛异常；引擎有兜底，但实现不应依赖兜底。

#### 10.6.3 指标

- `assay.runs.started` / `assay.runs` / `assay.run.duration`
- `assay.cases` / `assay.case.duration`
- `assay.batches` / `assay.batch.size` / `assay.store.append`

标签是 `suite` / `source` / `status`；不带 `run_id` / `case_id`，避免基数爆炸。

#### 10.6.4 测试

- 3 项核心 listener 测试：生命周期事件完整；listener 全部抛异常时评测仍然通过；run 失败触发 `onRunFailed`。
- 2 项 Micrometer 测试：真实引擎跑 2 个 case 断言指标；失败 run 计入 `status=failed`。
- 2 项 OTel 测试：run/case 父子 span；存储失败时 run span 以 ERROR 关闭。
- 4 项 Starter 测试：包含 listener bean 注入。

#### 10.6.5 追问

- **“为什么不用 AOP？”** 核心不依赖 Spring；SPI 更轻，CLI/测试/其他运行时都能用。
- **“指标标签会不会爆炸？”** 不带 run_id/case_id；suite/source 是有限集合，且截断到 64 字符。
- **“listener 拖慢评测怎么办？”** 默认 no-op；Micrometer 只做内存计数，导出由 registry 异步/外部决定；更细的 adapter/规则耗时需要 span，下一步做。
- **“OTel 呢？”** 已新增 `extensions/assay-tracing-otel`：run/case/batch span，case 挂 run，失败 run 会关闭 span；OTel 依赖仍只在扩展模块。

---

### 10.7 OTel Tracing：run/case 父子关系与失败关闭

#### 10.7.1 Span 结构

```text
assay.run
├── assay.case (C1)
├── assay.case (C2)
└── assay.batch
```

- `assay.run`：run_id、suite、source、case_count、status、duration_ms。
- `assay.case`：case_id、suite、hard_pass、soft_warnings、status。
- `assay.batch`：batch.size、duration_ms。
- case span 通过 `Context.current().with(runSpan)` 挂到 run span 下。

#### 10.7.2 为什么加 `onCaseStarted(runId, evalCase)`

原来的 `onCaseStarted(EvalCase)` 只有 case，没有 run_id；worker 线程无法找到父 run span。新增默认方法：

```java
default void onCaseStarted(String runId, EvalCase evalCase) {
    onCaseStarted(evalCase);
}
```

引擎调新方法；旧 listener 不用改，仍然收到 `onCaseStarted(EvalCase)`。

#### 10.7.3 失败关闭

`onRunFailed(runId, error, durationNanos)` 在 `runSuite` 失败且已经触发 `onRunStarted` 时调用：

- 从 map 取出 run span；
- 设置 `assay.status=failed`、`assay.error`；
- `setStatus(ERROR)`、`recordException`、`end()`；
- 移除上下文，避免悬挂 span。

OTel 测试模拟 `saveCases` 抛 `disk full`，断言 run span 状态是 ERROR 且已结束。

#### 10.7.4 测试

- 2 项 OTel 测试：成功 run 的 span 数量和父子关系；失败 run 的 span 关闭。
- 使用 `InMemorySpanExporter`，不依赖 Jaeger/Phoenix。
- 断言 `caseSpan.getParentSpanId() == runSpan.getSpanId()`。

#### 10.7.5 追问

- **“为什么 batch span 不挂 run？”** 当前 `onBatchSaved` 没有 run_id；可以后续扩回调，或让 listener 从线程上下文拿。先保证 run/case 正确。
- **“OTel 会不会拖慢？”** span 创建是内存操作，导出由 SDK/processor 决定；默认 sampler 可以关。
- **“为什么不用 Micrometer Tracing？”** 直接用 OTel API 更明确；Micrometer 指标和 OTel span 分工不同。
- **“怎么做 adapter/规则 span？”** 需要 stage 级 SPI；当前只做 run/case/batch，避免过度设计。

---

### 10.8 七个专题的“一句话总结”

- **TraceDiff**：用 LCS 把“哪一步开始不一样”变成可解释的字段证据，而不是一句“失败了”。
- **门禁**：用用例级、场景级、绝对级、数值级的多层约束，把“测试通过”变成“能不能发”。
- **恢复**：用批次、manifest、尾部修复和文件锁，保证崩溃后能继续，且不会把两次实验混在一起。
- **JDBC**：把 `Results` 从“文件实现”提升为可替换契约，代价是迁移/锁/事务要自己负责。
- **Starter**：把企业集成放外围，核心继续零依赖；没有 `ProjectAdapter` 就不猜怎么调 Agent。
- **Listener + Micrometer**：用零依赖 SPI 暴露生命周期，指标实现放扩展；观测失败不能影响评测结果。
- **OTel**：run/case/batch span，case 挂 run，失败 run 也会关闭 span；核心仍然零依赖。

---

## 十一、可观测性专项追问（13 题）

> 这一节只围绕 `EvaluationListener` + Micrometer。回答顺序建议：**定位 → SPI → 指标 → 基数 → 测试 → 边界 → 下一步**。

### 1. 为什么用 `EvaluationListener`，而不是在引擎里直接调 Micrometer？

**一句话**：核心零依赖，Micrometer 只是可选观察者。

展开：

- 引擎只依赖 JDK；直接依赖 Micrometer 会把版本、registry、exporter 带进核心。
- SPI 可以被测试 listener、日志 listener、OTel listener、Micrometer listener 复用。
- listener 是观察者，不参与评测判定；出问题不能改变结果。
- 代价：多一层间接调用，但默认 `NOOP`，开销可以忽略。

### 2. 为什么不用 AOP？

- 核心不是 Spring 应用，CLI 和测试都不需要容器；AOP 会引入代理/容器依赖。
- 评测生命周期是显式的，直接在 `runSuite` / `evaluateCase` / `saveCases` 埋点更清楚。
- 没有代理对象、没有切点匹配、没有 self-invocation 问题，测试也更容易。
- 结论：AOP 适合横切关注点很多的 Spring 服务；Assay 这种单 jar 工具，显式 SPI 更合适。

### 3. listener 在哪些线程执行？怎么保证线程安全？

- `onRunStarted` / `onRunFinished`：主线程。
- `onCaseStarted` / `onCaseFinished`：worker 线程，可能并发。
- `onBatchSaved`：主线程（批次由主循环落盘）。

Micrometer 实现的做法：

- case 计时用 `ThreadLocal<Long>`，每个 worker 自己记开始时间，不共享可变计时器。
- run 计时用 `ConcurrentHashMap<String, Long>`，run_id 只做本地 key，不进标签。
- `MeterRegistry` 本身线程安全，counter/timer 可以并发更新。
- 接口 Javadoc 明确要求实现线程安全；不能依赖“当前刚好是单线程”。

### 4. listener 抛异常会不会影响评测？

不会。`EvaluationEngine` 对每个回调都有 `try/catch RuntimeException` 保护，异常被吞掉，评测继续。

测试 `aBrokenListenerCannotBreakTheEvaluation` 里 listener 的每个方法都抛异常，run 仍然通过，`hard_failures = 0`。

设计原则：**观测失败不能变成发布失败**。但实现仍应尽量不抛，兜底不是免责。

### 5. 为什么指标标签不带 run_id / case_id？

这是**基数（cardinality）**问题。

- 每个 run_id / case_id 都是唯一值；如果做标签，时间序列数量 = run 数 × case 数。
- Prometheus 要为每个标签组合保存一条时间序列；基数爆炸会导致内存、抓取、查询全部变慢。
- run_id / case_id 更适合放在日志、Trace、 exemplar 里，而不是指标标签。
- 当前标签只有 `suite` / `source` / `status`，都是有限集合；`suite` / `source` 还截断到 64 字符。

一句话：**指标回答“整体趋势”，不回答“哪一个 case”；后者交给报告和 TraceDiff。**

### 6. 指标怎么测试？没有 Prometheus 怎么验证？

用 `SimpleMeterRegistry` + 真实引擎跑一个小 suite：

- 2 个 case，1 个 worker。
- 断言 `assay.cases{status="passed"} = 2`。
- 断言 `assay.runs{status="passed"} = 1`。
- 断言 `assay.case.duration` 的 count = 2。
- 断言 `assay.batches`、`assay.store.append` 至少 1 次。

不依赖外部 collector，也不需要起 Prometheus。Starter 另外用 `ApplicationContextRunner` 验证 listener bean 注入。

### 7. Micrometer 和 OpenTelemetry 有什么区别？为什么先做 Micrometer？

- **Micrometer**：JVM 指标门面 + registry，Spring Boot Actuator 默认集成，适合 counter/timer/gauge。
- **OpenTelemetry**：metrics + traces + logs 的 API/SDK/协议，适合跨服务 Trace 和供应商中立导出。
- 两者不是互斥：Micrometer 可以桥接 OTel，OTel 也可以有 Micrometer 桥。
- 现在两者都已有：Micrometer 出指标，OTel 出 run/case/batch span。先做 Micrometer 是因为依赖小、和 Spring Boot 生态近、能最快验证 SPI；OTel 需要额外的上下文设计，后做但不冲突。

### 8. 指标能替代报告 / TraceDiff 吗？

不能。

| | 指标 | 报告 / TraceDiff |
|---|---|---|
| 粒度 | 跨 run 聚合 | 单 run / 单 case |
| 回答 | 最近是否健康、慢在哪 | 哪一步、哪个字段变了 |
| 用途 | 告警、趋势、容量 | 诊断、复盘、发布证据 |

指标告诉你“失败率升高了”，TraceDiff 告诉你“第 2 步 route 从 SUPPORT 变成 GENERAL”。两者互补。

### 9. Spring Boot 里怎么接？没有 listener 会怎样？

注册一个 `EvaluationListener` bean 即可：

```java
@Bean
EvaluationListener assayMetrics(MeterRegistry registry) {
    return new MicrometerEvaluationListener(registry);
}
```

Starter 用 `ObjectProvider<EvaluationListener>` 注入；没有 bean 时用 `EvaluationListener.NOOP`，不会报错，也不会引入指标依赖。

### 10. 失败 run 没有 `onRunFinished` 怎么办？

已经补了 `onRunFailed(runId, error, durationNanos)`：

- `runSuite` 用一个 `RunState` 记录“是否已经触发 onRunStarted”；只有 start 成功后才在 catch 里发 `onRunFailed`。
- Micrometer 把失败 run 记成 `assay.runs{status="failed"}`，并记录 `assay.run.duration`。
- OTel 把 run span 设为 ERROR、`recordException`、`end()`，不会留下悬挂 span。
- 测试覆盖：存储 `saveCases` 抛异常时，核心 listener 收到 failed，Micrometer/OTel 都能断言。

仍然存在的边界：如果 `lockRun` 或 `startRun` 在 `onRunStarted` 之前失败，就没有“已开始”的 run 可失败，不会发 `onRunFailed`；这是有意的，避免把锁冲突算成一次失败评测。

### 11. 下一步加什么指标？怎么避免“指标很多但没用”？

优先加能对应 SLO 的指标：

- adapter 调用延迟 / 错误率（区分 online/offline）。
- 规则计算耗时（结构/行为/一致性/反馈）。
- LLM review 调用次数、延迟、失败率（非权威，但要观测）。
- 队列/在飞 case 数、重试次数。
- 存储错误数、恢复次数、锁等待。
- 失败 run 数、失败原因分类。

避免做法：

- 不要给每个 case/run 打标签。
- 不要只记录平均值；用 Timer/Histogram 看 P50/P95/P99。
- 不要加没有 dashboard/alert/owner 的指标。
- 指标命名要稳定，标签值要可枚举。

### 12. 怎么接到 Prometheus / Grafana？

在应用侧加：

- `micrometer-registry-prometheus`
- Spring Boot Actuator（`/actuator/prometheus`）

然后 Prometheus scrape，Grafana 查询。Assay 本身不内置 exporter，因为那是使用方的运行时选择；核心只负责发事件，扩展负责指标，registry/exporter 负责暴露。

示例查询：

```promql
# 硬失败率
rate(assay_cases_total{status="failed"}[5m])
/
rate(assay_cases_total[5m])

# case 耗时 P95
histogram_quantile(0.95, rate(assay_case_duration_seconds_bucket[5m]))

# 存储写入 P95
histogram_quantile(0.95, rate(assay_store_append_seconds_bucket[5m]))
```

### 13. OTel span 怎么保证父子关系？失败 run 会不会漏 span？

父子关系：

- 新增默认方法 `onCaseStarted(String runId, EvalCase evalCase)`；旧 listener 仍然收到 `onCaseStarted(EvalCase)`。
- `onRunStarted` 把 `Context.current().with(runSpan)` 存进 `ConcurrentHashMap`。
- worker 线程执行 case 时，用 runId 取出父 Context，`spanBuilder("assay.case").setParent(parent)`。
- 测试断言 `caseSpan.getParentSpanId() == runSpan.getSpanId()`。

失败关闭：

- `onRunFailed` 从 map 取出 run span，设置 `status=failed`、`error`，`setStatus(ERROR)`、`recordException`、`end()`。
- 测试模拟 `saveCases` 抛 `disk full`，断言 run span 状态 ERROR 且已结束。
- batch span 当前是独立 span；如果要求严格父子关系，需要把 run_id 加入 batch 回调。

### 30 秒回答模板

> “核心通过零依赖的 `EvaluationListener` 发 run/case/batch 生命周期事件，默认 no-op，listener 异常不会影响评测。Micrometer 扩展输出失败率、case P95、批量落盘延迟等指标，标签不带 run_id/case_id；OTel 扩展输出 run/case/batch span，case 挂 run，失败 run 会 recordException 并关闭。测试分别用 SimpleMeterRegistry 和 InMemorySpanExporter，不依赖外部 collector。”
