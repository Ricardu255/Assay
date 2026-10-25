# Assay

**Assay**（化验、检定）—— 一个与领域解耦的 Agent 自动评测、诊断、人工审核、回归和版本演化框架。

名字取自冶金行业的做法：一批金属要先取样化验，判定成分合格才决定是否收货。这个框架对候选版本的 Agent / Skill 做同样的事——取样、过门禁、给出结论——但**收货的决定权始终在人手里**：它产出证据和判定，不自动改写或替换生产版本。

它用于上线前的候选版本验证，帮助研发团队把改动从"测试通过"推进到"可以发布"的决策；线上回流的新 run、Trace 和人工反馈也可以进入下一轮迭代。

零运行时依赖：核心只用 JDK，构建只需 Maven。


## 快速开始

需要 JDK 17 或更高版本与 Maven。

**不懂 Java：先看 [QUICKSTART.md](QUICKSTART.md)。** 安装 Java 17 后，Windows 双击 `assay.cmd`，或在项目根目录运行 `assay.cmd quickstart`，按三个问题填入 Agent 地址、答案字段和期望关键字，会自动生成配置、跑评测并打开报告。

**Windows 首次体验：直接双击项目根目录的 `assay.cmd`。** 它会构建项目、运行一条无需 API Key 的示例，再自动打开报告界面。保持启动窗口运行，按 `Ctrl+C` 停止。macOS / Linux 可运行 `sh ./assay demo`。源码版需要 JDK 17+ 与 Maven 在 PATH 中；使用包含根目录 `assay.jar` 的分发目录时可省去 Maven。

启动器也可简写其他命令，例如 `assay.cmd serve --open-browser`。显式请求自动打开浏览器时，如果 8080 端口已被占用，会选择空闲端口并打开实际地址。

```bash
mvn -q clean package
```

最省事的验证方式是跑自带测试：不需要 API Key，不访问外部模型。

```bash
mvn -q test
```

跑一次端到端评测：

```bash
java -jar app/target/assay.jar run \
  --adapter io.assay.examples.EchoAdapter \
  --suite smoke \
  --cases examples/cases.example.jsonl \
  --collect-few-shot
```

在项目所在电脑上启动只读可视化界面：

```bash
java -jar app/target/assay.jar serve --output .assay/runs --port 8080
```

命令保持运行时，在同一台电脑的浏览器打开 [http://127.0.0.1:8080/](http://127.0.0.1:8080/)；按 `Ctrl+C` 关闭。`--output` 指向报告目录，可指定同时包含多类报告的父目录。界面展示运行、Trace、规则结果、场景统计、版本演化、范围审计和发布验证；总览还提供最近 7 天通过率趋势和按测试集健康表。可筛选失败用例或在浏览器中临时导入报告 JSON。服务只监听本机，查看和导入不会改写报告。

首次接入可打开界面的「开始评测」：选择 **HTTP 接口（推荐，无需写代码）**，填写地址、响应字段和通过条件，下载配置，再选择 JSONL 测试集检查格式、重复 ID 与必需字段，复制适用于 PowerShell 或 Bash 的运行命令。配置生成和文件检查在浏览器内完成；下载文件放到项目根目录，或将路径改为实际位置。命令由用户在项目根目录的另一个终端运行，完成后点击顶部「刷新」查看结果。

前端纯函数检查可用 Node.js 执行：`node --test assay-dashboard/src/test/js/dashboard.test.cjs`；运行仪表盘本身不需要 Node.js。

运行明细中的 Trace 以步骤卡片展示：失败步骤默认展开，可筛选失败、定位首个失败、展开全部，或复制完整的单步 JSON。步骤额外数据来自适配器记录的 `TraceEvent.fields`；未记录时会明确显示为空。运行页的「规则统计」按规则名展示评测数、通过数、通过率和缺失样本，低通过率规则排在前面；点击「查看趋势」可看同一 suite 最近 20 次运行的规则通过率。

顶部可选择浅色、深色或跟随系统，选择会保存在当前浏览器。颜色、字号、间距与状态样式统一使用主题变量；系统启用减少动态效果时，界面会关闭过渡和滚动动画。

### 打包给最终用户

```powershell
powershell -ExecutionPolicy Bypass -File scripts/package-dist.ps1
```

生成 `dist/assay.zip`，包含 `assay.jar`、`assay.cmd`、`assay`、`examples` 和 `QUICKSTART.md`；用户只需要 Java 17，不需要 Maven。

## 模块结构

```text
assay-core/                            # 领域模型、规则、评测引擎、演化、存储接口
assay-store-file/                      # 文件存储 ResultStore
assay-store-jdbc/                      # JDBC 存储 JdbcResultStore
assay-dashboard/                       # 只读 Dashboard 与静态资源
assay-cli/                             # CLI、适配器、沙箱、审计、选择、review
app/                                   # 分发模块：shade 成 assay.jar
extensions/assay-spring-boot-starter/  # 可选 Spring Boot 自动配置
extensions/assay-metrics-micrometer/   # 可选 Micrometer 指标
extensions/assay-tracing-otel/         # 可选 OpenTelemetry span
```

根目录是 Maven reactor，`mvn -q test` 会一次构建九个代码模块。`app` 通过 shade 把 core、两个 store、dashboard 和 cli 打成可执行的 `app/target/assay.jar`；只构建某个扩展时用 `mvn -q -pl :assay-spring-boot-starter -am test`。`app` 通过 enforcer 禁止引入 Spring/Micrometer/OTel，集成能力留在扩展模块。

范围审计页面可加载报告旁的 `diff.patch`，切换统一或分屏视图，并从发现定位到修改前或修改后的行。服务读取补丁时会核对报告的 `diff_sha256`，不一致则拒绝展示；导入 JSON 后也可选择本地补丁进行同样的检查。补丁查看上限为 8 MiB，页面最多展示 5,000 行、200 个文件。

宽屏下可以拖动用例与 Trace 之间的分隔条；左右键调节宽度，双击或 Enter 恢复平分，拖动中按 Escape 撤销。分栏宽度与折叠状态保存在当前浏览器。窄面板自动上下排列。用例列表支持上下键、Home / End、PageUp / PageDown，详情标签支持左右键。目录中的报告、嵌套报告、用例和筛选条件保存在页面地址中，可刷新恢复或复制链接；临时导入的 JSON 仍仅保留在当前页面。

## 通过 HTTP 接口测试自己的 Agent

普通的同步 JSON HTTP 接口不需要编写 Java 代码。推荐使用页面向导；也可以修改 `examples/http-agent.example.json` 的 `endpoint` 和 `output_path`，以及 `examples/http-cases.example.jsonl` 的请求和期望值，然后运行：

```powershell
.\assay.cmd run --adapter examples/http-agent.example.json --suite my-agent --cases examples/http-cases.example.jsonl
```

macOS / Linux 将 `.\assay.cmd` 换为 `sh ./assay`。启动器会自动构建；构建后也可使用 `java -jar app/target/assay.jar run ...`。

例如接口接收 `{"message":"hello agent"}`，返回 `{"data":{"answer":"hello"},"route":"ECHO"}`，配置为：

```json
{
  "type": "http",
  "name": "my-agent",
  "endpoint": "http://127.0.0.1:9000/agent",
  "output_path": "data.answer",
  "hard_gates": [
    {"name":"answer","actual":"final_output","operator":"contains","expected":"keyword"},
    {"name":"route","actual":"fields.route","operator":"eq","expected":"route"}
  ]
}
```

对应 JSONL 一行一个用例：

```jsonl
{"id":"HTTP-001","input":{"message":"hello agent"},"expected":{"keyword":"hello","route":"ECHO"}}
```

`input` 会原样序列化为 POST 请求体。若接口需要 `model`、`messages`、`session_id` 等字段，直接在 `input` 中填写完整 JSON 请求，使用同步响应设置（例如接口支持时设置 `stream:false`）。`expected` 不会发送给 Agent。

| 配置字段 | 用途 |
| --- | --- |
| `type` / `endpoint` | 必填，`type` 为 `http`，地址为 HTTP(S) URL；不接受 URL 中的用户名、密码或片段 |
| `output_path` | 回答所在字段，默认 `output`，支持 `data.answer`、`choices.0.message.content` 等点分隔路径 |
| `hard_gates` | 至少一条通过条件；`actual` 读取标准 Trace，`expected` 读取用例的 `expected`，或用 `value` 设置固定值 |
| `soft_quality` | 可选，规则格式与 `hard_gates` 相同，只产生软告警 |
| `bearer_token_env` | 可选，保存 Bearer Token 的环境变量名，不填写实际密钥 |
| `headers_env` | 可选，自定义请求头到环境变量名的映射，如 `{"X-API-Key":"AGENT_API_KEY"}`；变量值就是完整请求头值 |
| `trace_id_path` | 可选，默认 `trace_id`；接口未返回时生成本次请求的本地追踪 ID，并在 `raw.http.trace_id_source` 标明来源 |
| `events_path` | 可选，内部 TraceEvent 数组的响应路径；不配置时仅记录实测的 HTTP 耗时，不推断内部步骤 |

完整 JSON 响应保存在 `Trace.fields` 中，规则可检查 `fields.route`、`fields.data.score` 等业务字段。配置 `events_path` 后使用接口提供的步骤，HTTP 总耗时仍保存在 `raw.http.duration_ms`，避免把总耗时和内部步骤耗时重复计入行为规则。

需要鉴权时，在配置中添加 `"bearer_token_env":"AGENT_API_KEY"`，并在运行评测的**同一个终端**设置变量：PowerShell 使用 `$env:AGENT_API_KEY='你的密钥'`，Bash 使用 `export AGENT_API_KEY='你的密钥'`。向导不会要求填写实际密钥，请勿把密钥放入配置、请求体或提交到仓库。

接口必须返回 JSON 对象；当前支持同步 POST 和 `--source online`，不支持 SSE、流式输出或独立异步任务轮询。单次请求和响应读取遵守 `--timeout`（单位为秒，默认 30），响应上限为 8 MiB，重定向不会跟随；HTTP 错误、超时、无效 JSON、缺少响应字段会生成失败证据。只有配置了 CLI `--retries` 才会重试，可能重复调用有副作用的接口。

## 通过轨迹文件评测外部 Agent

如果 Agent 不在本机运行，或需要先离线比较两批导出轨迹，可以完全用 JSON 配置，无需编写 Java 适配器。归一化轨迹就是 `NormalizedTrace` 的 JSON 投影：

```json
{
  "version": "2026-10-19",
  "runs": [
    {
      "case_id": "CASE-001",
      "trace": {
        "trace_id": "trace-001",
        "final_output": {"answer": "..."},
        "events": [
          {"module": "Planner", "action": "plan", "status": "ok", "duration_ms": 12, "fields": {}},
          {"module": "Tool", "action": "query_order", "status": "ok", "duration_ms": 8, "fields": {"order_id": "A1001"}}
        ],
        "fields": {"route": "SUPPORT"}
      }
    }
  ]
}
```

对应配置：

```json
{
  "type": "trace_file",
  "runs": "exports/baseline.json",
  "format": "normalized",
  "target_type": "skill",
  "target_id": "intent-router",
  "target_version": "1",
  "hard_gates": [{"name": "route", "actual": "fields.route", "operator": "eq", "expected": "route"}]
}
```

运行时使用 `--source offline`：

```bash
java -jar app/target/assay.jar evolve \
  --baseline-adapter examples/trace-candidate.example.json \
  --candidate-adapter examples/trace-regressed.example.json \
  --candidate examples/evolution.trace-regressed.candidate.json \
  --policy examples/evolution.regressed.policy.json \
  --improvement examples/evolution.improvement.jsonl \
  --regression examples/evolution.regression.jsonl \
  --holdout examples/evolution.holdout.jsonl \
  --source offline
```

该示例会返回 `rollback`，并指出第 2 步 `IntentRouter/route` 的 `fields.route` 从 `SUPPORT` 变为 `GENERAL`。单套评测同样可用 `assay run --adapter <配置> --cases <测试集> --source offline`。

`format` 设为 `openinference` 时读取 Phoenix/OTel 导出：支持扁平 attributes、OTLP 属性数组、`traces[].spans` 和 `resourceSpans[].scopeSpans[].spans`。用 `assay.case_id` 属性关联用例，必要时回退到 `trajectiq.task_id`。`runs` 相对当前工作目录解析；`target_type`、`target_id`、`target_version` 会覆盖文件中的目标身份，`evolve` 的版本匹配依赖它们。用例缺少导出轨迹时会记录为硬失败，不会静默跳过。

## 高级接入：领域项目提供四项能力

需要 SDK、本地函数、离线 Trace 或特殊协议时，领域项目实现一个 Java 适配器，回答四个问题：

1. **`callAgent(case, context)`** —— 怎么调用 Agent。HTTP、SDK、子进程或本地函数调用都行。
2. **`readTrace(handle, case)`** —— 怎么把原生 Trace 转成框架的 `NormalizedTrace`。
3. **`hardGates`** —— 哪些规则不通过就阻断发布。
4. **`softQuality`** —— 哪些规则只产生质量告警和人工复核候选。

```java
public final class MyAdapter implements ProjectAdapterFactory {
    @Override
    public ProjectAdapter create() {
        return new ProjectAdapter(
                "my-agent",
                MyAdapter::callAgentOverHttp,
                MyAdapter::toNormalizedTrace,
                List.of(Rule.of("route", "fields.route", "route")
                        .suspectedModules("IntentRouter")),
                List.of(Rule.of("answer_length", "final_output", "min", "min_length")));
    }
}
```

写完用类名引用：

```bash
java -jar app/target/assay.jar run --adapter com.example.MyAdapter --suite smoke --cases smoke.jsonl
```

这里有一个 Java 的常规约束：`java -jar` 会忽略 `-cp`，所以主类路径上找不到的适配器要么放在独立的 jar 里（下面的第二种方式），要么改用 `-cp` 方式启动：

```bash
java -cp "app/target/assay.jar:build/classes" io.assay.cli.AssayCli \
  run --adapter com.example.MyAdapter --suite smoke --cases smoke.jsonl
```

**适配器单独打包**（推荐，框架与领域项目解耦发布）：在自己的工程里引用框架 jar 编译适配器，然后在 `META-INF/services/io.assay.engine.ProjectAdapterFactory` 里写上实现类名：

```bash
java -jar app/target/assay.jar run --adapter path/to/adapter.jar --suite smoke --cases smoke.jsonl
```

如果 jar 里注册了多个实现、或者没有注册，用 `--adapter-class`（`evolve` 上是 `--baseline-adapter-class` / `--candidate-adapter-class`）显式指定用哪一个：

```bash
java -jar app/target/assay.jar run \
  --adapter path/to/adapter.jar --adapter-class com.example.MyAdapter \
  --suite smoke --cases smoke.jsonl
```

适配器 jar 在加载时被整体读入内存，不占用文件句柄——运行结束后可以立即重建或替换它。

仓库里的 `examples/` 提供三个可直接运行的参考实现。

规则是纯数据：`actual` 是从 Trace 读字段的路径，`expected` 是从 case 的 `expected` 块读字段的路径，`operator` 支持 `eq`、`ne`、`contains`、`not_contains`、`min`、`max`、`in`、`exists`，以及 `sequence_contains` / `sequence_eq`。序列规则默认把 `events` 中的 `module/action` 作为步骤 key；步骤可带 `args` 匹配事件 `fields`，嵌套数组表示必须连续的步骤块。`sequence_contains` 要求有序子序列，`sequence_eq` 要求完全一致；运行页规则检查会用步骤 chips 展示实际与期望序列。

```json
{"name":"tool_chain","actual":"events","operator":"sequence_contains",
 "value":["Router/route",[{"step":"Tool/search","args":{"query":"refund"}},"Tool/answer"]]}
```

## 标准 Trace

`readTrace` 应返回 `NormalizedTrace`：

- `traceId` 和 `finalOutput` 是框架的完整性字段，缺失即硬失败。
- `events` 保存 `module`、`action`、`status`、`durationMs`、`error`，供行为异常和模块定位使用。
- `fields` 保存领域项目希望用规则检查的结构化结果。
- `feedback` 可保存 `explicit_negative`、`repeated_question`、`rephrased` 等弱信号。
- `targetType`、`targetId`、`targetVersion` 用于区分 Agent、普通 Skill 和测评 Skill。

字段命名和业务含义由领域项目决定，框架只读路径和规则。

## 四层评测

1. **结构层** —— Trace、最终输出，以及项目声明的硬门禁和软质量字段。
2. **行为层** —— 步骤数、重试、延迟、重复 module/action 和模块错误。
3. **一致性层** —— 只对启用 `consistency_check` 的高价值节点做多次运行，差异只进入人工复核候选。
4. **反馈层** —— 隐式反馈只生成候选，不自动判定 badcase。

人工结论与自动报告分开保存，可追溯但互不覆盖。

## 命令行

| 命令 | 用途 |
|---|---|
| `run` | 评测一个外部提供的测试集 |
| `release` | 依次执行 regression、smoke、可选 full，任一阶段硬门禁失败即停止 |
| `select-tests` | 根据当前 Git Diff 推荐该跑哪套测试 |
| `audit-scope` | 按需求与验收标准审计一次改动是否越界 |
| `evolve` | 对比基线与候选版本，给出 accept / reject / rollback |
| `evolve-auto` | 在沙箱内自动诊断、生成候选、评测、迭代 |
| `review` | 保存人工最终结论 |
| `export` | 导出已确认的 regression 或 few-shot 候选 |
| `promote-review` | 把一条审核样本晋级到 improvement / regression / holdout |

退出码是一份契约：`0` 正常，`1` 硬门禁失败或决策为负，`2` 用法错误或结果需要人工复核。需要人看的运行不会看起来像通过。

所有子命令都有可直接运行的示例：

```bash
# 测试集选择（只跑确定性规则，不调用模型）
java -jar app/target/assay.jar select-tests --repository path/to/project

# 演化对比
java -jar app/target/assay.jar evolve \
  --baseline-adapter 'io.assay.examples.IntentRouterAdapters$Baseline' \
  --candidate-adapter 'io.assay.examples.IntentRouterAdapters$Candidate' \
  --candidate examples/evolution.candidate.json \
  --policy examples/evolution.policy.json \
  --improvement examples/evolution.improvement.jsonl \
  --regression examples/evolution.regression.jsonl \
  --holdout examples/evolution.holdout.jsonl

# 自动演化（确定性生成候选，不调用模型）
java -jar app/target/assay.jar evolve-auto \
  --auto-adapter io.assay.examples.IntentRouterAutoEvolution \
  --policy examples/evolution.policy.json \
  --improvement examples/evolution.improvement.jsonl \
  --regression examples/evolution.regression.jsonl \
  --holdout examples/evolution.holdout.jsonl \
  --max-rounds 1 --max-candidates-per-round 2
```

Windows PowerShell 里嵌套类的 `$` 需要反引号转义；不确定时把适配器写成顶层类。

## 产物

每次 `run` 生成：

- `results.json` —— 逐 case 事实、规则结果和 Trace，是机器读取的事实源。
- `report.md` —— 硬失败、软告警、场景分布和疑似模块，由 JSON 生成，供人阅读。
- `scenario_stats.json` —— 按场景和 `targetType:targetId` 统计样本量、通过率和 95% 置信区间（Wilson 区间，避免小样本被读成精确值）。
- `review_queue.jsonl` —— 等待人工确认的报告。
- `few_shot_candidates.jsonl` —— 通过全部门禁和质量检查的成功路径候选。

每次 `evolve` 额外生成 `evolution.json` 和 `evolution_report.md`；`evolution.json` 的 `comparisons.*.case_transitions` 保存逐用例状态迁移和首错证据，`rule_deltas` 保存规则通过率变化；每次 `evolve-auto` 额外生成 `auto_evolution.json` 和 `auto_evolution_report.md`；`audit-scope` 生成 `scope_audit.json`、`report.md` 和完整的 `diff.patch`。

## 架构

```text
领域适配层：Agent 调用、原生 Trace 转换、硬门禁、软质量
        ↓
核心评测层：结构、行为、一致性、隐式反馈
        ↓
流程编排层：run / regression → smoke → full
演化编排层：baseline ↔ candidate → accept / reject / rollback
        ↓
外围能力：结果存储、Markdown/JSON、人工审核、可选 LLM
```

| 关注点 | 位置 |
|---|---|
| 领域适配 | `engine.ProjectAdapterFactory`、`engine.AdapterLoader`、`model.ProjectAdapter` |
| 数据模型 | `model.*`（不可变 `record`，`CaseResult` 可变） |
| 评测规则 | `rules.Rules`、`rules.ValueOps`、`rules.RuleConfig` |
| 评测执行 | `engine.EvaluationEngine`、`engine.CaseLoader`、`engine.EvaluationListener` |
| 结果存储 | `store.Results`（接口）、`store.ResultStore`、`store.jdbc.JdbcResultStore`、`store.Stores` |
| 报告产物 | `report.Reporting`、`report.Fmt`、`audit.ScopeAuditReport` |
| 可选 LLM | `llm.OpenAiCompatibleReviewer`、`llm.OpenAiCompatibleTextEvolver` |
| 版本演化 | `evolve.EvolutionEngine`、`evolve.TraceDiff` |
| 自动演化 | `autoevolve.AutoEvolutionLoop`、`workspace.TextArtifactWorkspace` |
| 测试集选择 | `diff.DiffAnalysis`、`selection.TestSelectionService` |
| 范围审计 | `audit.ScopeAudit` |
| 进程与容器 | `process.AgentProcessRunner`、`process.ContainerRunner` |
| 轨迹接入 | `opentrace.OpenAiTrace`、`opentrace.OpenAiTraceProcessor`、`trace.TraceFileAdapter` |
| 评测指标 | `metrics.EvaluatorMetrics`、`review.ReviewSamples` |

核心规则不依赖命令行、存储或 LLM；LLM 只产出语义分析、疑似模块、修改建议和 few-shot 候选，永远不参与硬门禁判定。

崩溃恢复、首错诊断和隔离边界的实现细节、故障矩阵与测试见 [docs/engineering-deep-dive.md](docs/engineering-deep-dive.md)；10,000 条合成用例的文件/JDBC 与多步参考 Agent 基线见 [docs/benchmark.md](docs/benchmark.md)，可用 `python scripts/benchmark.py --count 10000 --workers 20` 复现。

## 存储、恢复与并发

结果按运行分目录保存为 JSON：元数据、case 结果和 case 身份清单各一份。case 结果以 JSONL 追加写入、读取时后写覆盖，语义上等价于 upsert。只有最后一行未写完时才允许恢复，下一次追加前会截断该尾部；完整但缺少换行的记录会补上分隔符，UTF-8 字符写到一半也能恢复。中间损坏或缺少 case 身份的记录会明确报错。运行结束时先压缩结果，再保存最终状态。

结果以最多 32 条的有界批次落盘，异常退出后未提交的批次会在断点恢复时重跑。要恢复，首次运行和恢复运行都传入同一个 `--run-id` 与 `--run-identity`（绑定适配器和被测产物的稳定身份），恢复时再加 `--resume`；数据集内容、版本身份或执行配置任一不一致都会被拒绝，而不是把两次实验混进同一份报告。

同一次运行在每个 case 上都会计算内容哈希并记录身份清单，因此恢复时能明确区分"新增 case"和"case 内容被改过"。

跨进程互斥用文件锁实现：同一条运行或同一个演化循环在同一时刻只能有一个持有者，第二个持有者会立即失败而不是排队。`evolve-auto` 的检查点（`.assay/workspaces/<loop-id>/checkpoint.json`）原子写入，异常、时间预算或调用预算中止后，可以提高预算或修复外部故障再用同样的参数续跑。

存储层只有 `store.Results` 一个接口，引擎只依赖它。默认实现是文件存储；需要数据库时可以用 `JdbcResultStore`，`--store` 传入 `jdbc:` URL 即可切换：

```bash
# 以 H2 为例；驱动不在框架 jar 内，需要自己放到 classpath
java -cp "app/target/assay.jar:drivers/h2-2.2.224.jar" io.assay.cli.AssayCli run \
  --adapter io.assay.examples.EchoAdapter \
  --suite smoke \
  --cases examples/cases.example.jsonl \
  --store "jdbc:h2:./.assay/assay-db" \
  --output .assay/runs
```

Windows 的 classpath 分隔符是 `;`。数据库凭据可通过 `ASSAY_DB_USER` / `ASSAY_DB_PASSWORD` 环境变量提供；表结构会在首次打开时自动创建。H2 file URL 会自动补 `DB_CLOSE_DELAY=-1`，避免关闭最后一个连接后重连变成 read-only。JDBC 存储用 `assay_lock` 表实现跨进程 run 锁，如果进程在持有锁时崩溃，需要人工清理残留的锁行（或删库重建测试库）——这是当前实现的已知边界，不是自动 TTL。

### Spring Boot Starter（可选）

`extensions/assay-spring-boot-starter` 提供 Spring Boot 自动配置，不改变核心的零依赖设计：

```yaml
assay:
  store:
    type: file        # file | jdbc
    path: .assay/store
  workers: 8
  retries: 0
  timeout-seconds: 30
  collect-few-shot: false
```

它会自动装配 `Results`，并在存在 `ProjectAdapter` bean 时装配 `EvaluationEngine`。选择 `jdbc` 时需要提供一个 `DataSource` bean。构建方式：

```bash
# 整个 reactor（主项目 + Starter + Micrometer）
mvn -q test

# 只构建 Starter 及其依赖
mvn -q -pl :assay-spring-boot-starter -am test
```

完整说明见 [extensions/assay-spring-boot-starter/README.md](extensions/assay-spring-boot-starter/README.md)。

### 可观测性（可选）

核心通过零依赖的 `EvaluationListener` 暴露 run / case / batch 生命周期；默认 no-op，不改变评测结果。可选的 [Micrometer 扩展](extensions/assay-metrics-micrometer/README.md) 提供：

- `assay.runs`、`assay.cases`、`assay.case.duration`
- `assay.batches`、`assay.batch.size`、`assay.store.append`
- `assay.runs.started`、`assay.run.duration`

标签只包含 `suite` / `source` / `status`，不带 `run_id` / `case_id`，避免时间序列基数爆炸。Starter 会自动注入容器里的 `EvaluationListener` bean。

可选的 [OpenTelemetry 扩展](extensions/assay-tracing-otel/README.md) 提供 `assay.run` / `assay.case` / `assay.batch` span，以及 `assay.adapter` / `assay.rules` / `assay.llm` / `assay.store` stage 子 span：adapter/rules/llm 挂 case，store 和 batch 挂 run；run 失败时会记录错误并关闭 span，不会留下悬挂 span。

## 测试集选择

`select-tests` 通过确定性规则、AI 和人工三层判断当前改动该跑哪套测试。规则提供安全底线，AI 阅读改动判断真实影响范围，人工只复核低置信度、规则与 AI 冲突以及高风险变更。AI 不能把规则要求的测试强度降级。

- `smoke` —— 文档、UI 和报告展示等低风险改动。
- `regression` —— Planner、Prompt、安全规则、追问策略、质检逻辑和一般行为改动。
- `full` —— 生成、RAG/检索、Agent 核心逻辑或评测结果结构改动。

本地模型（回环地址、私有 IP 或 `.local`）默认接收完整 diff；第三方 API 强制只接收脱敏摘要，摘要只含文件数量、扩展名、改动行数、文件类别和通用影响信号，不含源码、文件路径、URL 或具体值。

## 范围审计

`audit-scope` 根据任务需求、验收标准和两个 commit 之间的 Git Diff，结合确定性检查、可选语义分析和相关测试，筛出值得人工关注的改动并整理可追溯证据。它辅助人工审查，不自动批准、合并或发布。

任务说明写成 JSON：

```json
{
  "requirement": "将订单金额计算改为按新规则取整",
  "acceptance_criteria": ["金额结果符合新规则", "现有支付流程保持可用"],
  "allowed_paths": ["src/orders/**"],
  "forbidden_paths": ["src/payments/**"]
}
```

发现分为明确越界、疑似越界、合理关联和证据不足。明确禁止路径由确定性规则识别。模型发现只有在文件、Diff 侧别和行号都对应本次真实变更时才保留，并且完整记录每条模型发现的校验与处置（保留、合并重复、拒绝及原因）。`base` 行号对应删除前代码，`target` 行号对应新增后代码。

测试通过只证明已运行用例通过，不证明没有越界修改——报告会把测试未执行、覆盖缺口和证据不足分别列出。

## 版本演化

`evolve` 同时验证基线版本和候选版本。三个数据集职责固定，内容与业务口径由领域项目提供：

- `improvement` —— 候选版本声称要改善的问题。
- `regression` —— 已经确认、不能复发的历史能力。
- `holdout` —— 候选生成过程没有使用的留出样本。

策略文件可以约束 regression、holdout 和数值目标。目标可直接使用 `hard_pass`、`soft_warning_count`、`latency_ms`、`steps`、`llm_calls`、`input_tokens`、`output_tokens`、`total_tokens` 和 `cost_usd`，也可以从标准结果路径读取业务字段；支持 `mean`、`sum`、`min`、`max`、`p50`、`p95`、`p99` 聚合，以及最大允许退化和最低改善幅度。`maximum` 是候选自身的绝对上限，与基线无关。

`quality_gates` 补充绝对门禁：按角色设置最低硬通过率、最大回归条数、最大关键回归条数和最小样本数。它不替代 `objectives` 的相对退化判断，而是回答“这个候选本身是否可接受”。例如：

```json
{
  "quality_gates": [
    {
      "roles": ["regression", "holdout"],
      "minimum_hard_pass_rate": 0.95,
      "maximum_task_regressions": 0,
      "maximum_critical_task_regressions": 0,
      "minimum_samples": 10
    }
  ]
}
```

完整示例见 `examples/evolution.quality-policy.example.json`。

`rule_stats` 汇总每条命名规则在当前运行的通过率，`rule_deltas` 给出基线到候选的变化；工具选择、参数校验、答案覆盖等规则因此可以统一比较，不需要框架硬编码工具名。`evolution_report.md` 和 Dashboard 都会展示规则通过率与有效样本数。

策略字段采用严格校验：数值必须有限、比例必须在 `[0,1]`，计数必须是范围内的整数，布尔值必须使用 JSON 布尔类型。显式填写的非法值不会退回默认值；Java 构造器执行相同校验，同名目标也会被拒绝。

缺失 Trace 或指标会保留为 `null`，不会当作零延迟或零调用。演化指标中的 `metric_coverage` 和 `objective_coverage` 记录 `sample_count`、`missing_count`、`case_count`，报告展示目标的有效样本数。必需目标必须覆盖全部 case，聚合结果溢出也视为缺失。

`scenarioGates` 可以为高风险或小样本场景单独设置最低样本数、最低通过率和允许退化，避免被全量平均值掩盖。

决策含义：

- `accept` —— 目标问题获得可测量改善，且保护集没有超过策略允许的退化。
- `reject` —— 候选没有达到声明的改善目标。
- `rollback` —— 候选破坏 regression、holdout、版本身份或必要指标。

每次演化保存完整的基线与候选运行结果、`evolution.json`、`evolution_report.md` 和存储中的审计记录。

### 用例级回归与首错定位

`evolve` 不只比较聚合指标，还会把每条用例标记为稳定通过、回归、恢复或持续失败。回归用例会定位第一处可见差异：步骤增删、模块/动作变化、字段变化、步骤报错、最终输出或 Trace 字段变化。诊断直接读取已经评测过的 Trace，不重跑 Agent；每次运行都会变化的字段可以通过用例 `metadata.trace_diff_ignore` 忽略（默认已忽略 span id、父 span id 和时间字段）。

```bash
java -jar app/target/assay.jar evolve \
  --baseline-adapter 'io.assay.examples.IntentRouterAdapters$Candidate' \
  --candidate-adapter 'io.assay.examples.IntentRouterAdapters$Regressed' \
  --candidate examples/evolution.regressed.candidate.json \
  --policy examples/evolution.regressed.policy.json \
  --improvement examples/evolution.improvement.jsonl \
  --regression examples/evolution.regression.jsonl \
  --holdout examples/evolution.holdout.jsonl
```

该候选会返回 `rollback`，`evolution_report.md` 会指出 `EVOLVE-HOLDOUT-001` 的第 2 步 `IntentRouter/route` 字段从 `SUPPORT` 变为 `GENERAL`；Dashboard 的版本演化页也会展示同样的证据。用例在 `metadata` 中设置 `"critical": true` 后，回归清单会单独标记关键用例。

### 文本型自动演化

自动演化适配器实现 `AutoEvolutionAdapterFactory`，提供基线文本产物、诊断器、候选生成器和"按产物构建运行适配器"的函数。

```bash
java -jar app/target/assay.jar evolve-auto \
  --auto-adapter com.example.MyAutoEvolution \
  --policy examples/evolution.policy.json \
  --improvement improvement.jsonl --regression regression.jsonl --holdout holdout.jsonl \
  --max-rounds 1 --max-candidates-per-round 2 --max-elapsed-seconds 300 --max-evolver-calls 4
```

`TextArtifactWorkspace` 在 `.assay/workspaces` 中保存基线快照和候选产物，领域项目的原文件不被触碰。基线可以是单个 UTF-8 文本文件，也可以是文本目录。

多文件候选有两种表达方式：用 `TextCandidate.files` 给出完整内容，或用 `TextCandidate.operations` 执行受限的 `write`、`delete`、`move`；路径必须是用 `/` 的相对文件路径，盘符、冒号、控制字符、尾随空格或点以及符号链接路径会被拒绝。文件操作执行前再次验证最终路径仍在候选目录内，越界或冲突的文件操作会在复制候选之前被拒绝，回滚候选不会覆盖基线目录。

多文件候选先在同级暂存区域完成复制、修改、内容哈希和 manifest，再整体移动到最终目录。`.assay-staging` 是保留的候选 ID，用于隔离暂存区域。中断留下的临时目录，以及旧版本未提交的无 manifest 目录，会在重试时重建；已提交候选会校验哈希，内容被改动时拒绝复用。

时间预算在阶段边界检查，不会强杀正在执行的领域调用；单次调用仍由 `--timeout` 和领域适配器负责。`--max-evolver-calls` 只统计诊断器和候选生成器的调用次数，不等同于被测 Agent 的 token 或供应商账单——业务成本应通过 Trace 自定义指标进入评测策略。

### 代码型 Agent

代码候选可以把 `changeType` 设为 `code`，并由领域适配器用 `AgentProcessRunner` 启动。候选文件仍由 `TextArtifactWorkspace` 放进独立目录，进程以该目录为工作目录运行，超时会终止整棵进程树并保留 stdout/stderr。每个输出流默认最多保留 1 MiB，一旦超限立即终止进程树并抛出 `OutputLimitExceededException`，上限可通过 `maxOutputBytes` 调整。

按信任等级可以选进程 Runner 或容器 Runner：

```java
ContainerRunner.runAgentContainer(
        "python:3.12",
        List.of("python", "agent.py"),
        candidateDirectory,
        ContainerRunner.Options.defaults(),
        60);
```

容器默认禁用网络、只读挂载候选目录、使用只读容器文件系统、移除全部 capabilities，并限制 CPU、内存和 PID，为候选执行提供清晰的资源与权限边界。

## 可选 LLM

不配置 LLM 时，确定性评测、报告、人工审核和回归流程全部照常运行。需要语义分析时设置：

```bash
export ASSAY_MODEL=your-model
export ASSAY_BASE_URL=https://provider.example/v1
export ASSAY_API_KEY=...
```

运行时加 `--use-llm`。LLM 输出始终标记为非权威建议，分析失败不会改变评测事实。

## 测试

```bash
mvn -q test
```

覆盖范围：

- **评测闭环** —— 硬门禁、软告警、人工审核、few-shot 候选、release 分阶段执行与提前停止。
- **四层规则** —— 结构、行为、一致性、隐式反馈，以及规则比较的边界语义。
- **运行完整性与恢复** —— 空测试集与重复 case id 在建运行前就被拒绝；数据集、版本身份或执行配置变化会拒绝复用；批次写入被打断后重放未提交的那一批；截断尾部、UTF-8 半字符、缺少换行与再次中断的恢复；中间日志损坏和失败追加不会生成虚假结果；重复 trace id 落为硬失败且不重复记数。
- **并发** —— 跨进程文件锁（测试会真的启动第二个 JVM 争抢同一把锁）、两个进程并发写不同运行不丢数据、适配器的并发上限。
- **演化** —— accept / reject / rollback 三类决策、场景门禁、绝对质量门禁、回归条数与关键回归、规则通过率、P95 聚合、严格配置校验、目标缺值与数值溢出、真实零值和缺失数据的区分、指标覆盖率。
- **用例级诊断** —— LCS 步骤对齐、插删步骤不级联、字段/答案/Trace 字段差异、易变字段忽略、缺失 Trace、失败聚类。
- **外部轨迹** —— 归一化轨迹、OpenInference 扁平属性与 OTLP 数组、Phoenix `traces` 包装、缺失 case 硬失败、目标身份覆盖。
- **自动演化** —— 时间与调用预算、可重试失败重试一次、破坏性候选回退、检查点续跑复用已落盘候选、候选目录提交失败与遗留目录的恢复、Windows 盘符和文件流路径的拒绝。
- **进程与容器** —— 超时终止整棵进程树（断言孙进程确实消失）、输出上限与截断标记、容器命令的安全参数。
- **测试集选择** —— 确定性规则的最低档位、AI 只能提高不能降低、本地/远程输入边界。
- **范围审计** —— 发现分类与行号侧别、模型发现的校验与处置轨迹、报告产物。
- **OpenAI Trace** —— span 类型识别的各种写法、token 与错误聚合、按 trace id 分组与非法输入拒绝。
- **其余** —— JSON 编解码、评测指标、审核样本晋级、命令行退出码（`0` / `1` / `2`）。

## 扩展点

- **更强的隔离**：把候选执行换成容器 Runner，框架侧不用改。
- **换存储**：Java 调用方可注入自己的 `Results` 实现；当前 CLI 的 `--store` 指定本地存储目录。
- **换 LLM 供应商**：`OpenAiCompatibleReviewer` 走 OpenAI 兼容协议；换协议时实现 `LlmReviewer` 与 `TestSelectionService.JsonReviewer`。
- **接更多 Trace 来源**：`NormalizedTrace` 是稳定契约；`trace_file` 已支持归一化 JSON 和 OpenInference/OTLP 导出，其他采集方式实现 `TraceReader` 即可。
