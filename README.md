# Assay

**Assay**（化验、检定）—— 一个与领域解耦的 Agent 自动评测、诊断、人工审核、回归和版本演化框架。

名字取自冶金行业的做法：一批金属要先取样化验，判定成分合格才决定是否收货。这个框架对候选版本的 Agent / Skill 做同样的事——取样、过门禁、给出结论——但**收货的决定权始终在人手里**：它产出证据和判定，不自动改写或替换生产版本。

它用于上线前的候选版本验证，帮助研发团队把改动从"测试通过"推进到"可以发布"的决策；线上回流的新 run、Trace 和人工反馈也可以进入下一轮迭代。

零运行时依赖：核心只用 JDK，构建只需 Maven。


## 快速开始

需要 JDK 17 或更高版本与 Maven。

```bash
mvn -q clean package
```

最省事的验证方式是跑自带测试：不需要 API Key，不访问外部模型。

```bash
mvn -q test
```

跑一次端到端评测：

```bash
java -jar target/assay.jar run \
  --adapter io.assay.examples.EchoAdapter \
  --suite smoke \
  --cases examples/cases.example.jsonl \
  --collect-few-shot
```

## 领域项目只提供四项能力

评测逻辑与业务语义解耦。领域项目实现一个适配器，回答四个问题：

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
java -jar target/assay.jar run --adapter com.example.MyAdapter --suite smoke --cases smoke.jsonl
```

这里有一个 Java 的常规约束：`java -jar` 会忽略 `-cp`，所以主类路径上找不到的适配器要么放在独立的 jar 里（下面的第二种方式），要么改用 `-cp` 方式启动：

```bash
java -cp "target/assay.jar:build/classes" io.assay.cli.AssayCli \
  run --adapter com.example.MyAdapter --suite smoke --cases smoke.jsonl
```

**适配器单独打包**（推荐，框架与领域项目解耦发布）：在自己的工程里引用框架 jar 编译适配器，然后在 `META-INF/services/io.assay.engine.ProjectAdapterFactory` 里写上实现类名：

```bash
java -jar target/assay.jar run --adapter path/to/adapter.jar --suite smoke --cases smoke.jsonl
```

如果 jar 里注册了多个实现、或者没有注册，用 `--adapter-class`（`evolve` 上是 `--baseline-adapter-class` / `--candidate-adapter-class`）显式指定用哪一个：

```bash
java -jar target/assay.jar run \
  --adapter path/to/adapter.jar --adapter-class com.example.MyAdapter \
  --suite smoke --cases smoke.jsonl
```

适配器 jar 在加载时被整体读入内存，不占用文件句柄——运行结束后可以立即重建或替换它。

仓库里的 `examples/` 提供三个可直接运行的参考实现。

规则是纯数据：`actual` 是从 Trace 读字段的路径，`expected` 是从 case 的 `expected` 块读字段的路径，`operator` 支持 `eq`、`ne`、`contains`、`not_contains`、`min`、`max`、`in`、`exists`。

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
java -jar target/assay.jar select-tests --repository path/to/project

# 演化对比
java -jar target/assay.jar evolve \
  --baseline-adapter 'io.assay.examples.IntentRouterAdapters$Baseline' \
  --candidate-adapter 'io.assay.examples.IntentRouterAdapters$Candidate' \
  --candidate examples/evolution.candidate.json \
  --policy examples/evolution.policy.json \
  --improvement examples/evolution.improvement.jsonl \
  --regression examples/evolution.regression.jsonl \
  --holdout examples/evolution.holdout.jsonl

# 自动演化（确定性生成候选，不调用模型）
java -jar target/assay.jar evolve-auto \
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

每次 `evolve` 额外生成 `evolution.json` 和 `evolution_report.md`；每次 `evolve-auto` 额外生成 `auto_evolution.json` 和 `auto_evolution_report.md`；`audit-scope` 生成 `scope_audit.json`、`report.md` 和完整的 `diff.patch`。

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
| 评测规则 | `rules.Rules`、`rules.ValueOps` |
| 评测执行 | `engine.EvaluationEngine`、`engine.CaseLoader` |
| 结果存储 | `store.Results`（接口）、`store.ResultStore` |
| 报告产物 | `report.Reporting`、`report.Fmt`、`audit.ScopeAuditReport` |
| 可选 LLM | `llm.OpenAiCompatibleReviewer`、`llm.OpenAiCompatibleTextEvolver` |
| 版本演化 | `evolve.EvolutionEngine` |
| 自动演化 | `autoevolve.AutoEvolutionLoop`、`workspace.TextArtifactWorkspace` |
| 测试集选择 | `diff.DiffAnalysis`、`selection.TestSelectionService` |
| 范围审计 | `audit.ScopeAudit` |
| 进程与容器 | `process.AgentProcessRunner`、`process.ContainerRunner` |
| OpenAI Trace | `opentrace.OpenAiTrace`、`opentrace.OpenAiTraceProcessor` |
| 评测指标 | `metrics.EvaluatorMetrics`、`review.ReviewSamples` |

核心规则不依赖命令行、存储或 LLM；LLM 只产出语义分析、疑似模块、修改建议和 few-shot 候选，永远不参与硬门禁判定。

## 存储、恢复与并发

结果按运行分目录保存为 JSON：元数据、case 结果和 case 身份清单各一份。case 结果以 JSONL 追加写入、读取时后写覆盖，语义上等价于 upsert，而被中断截断的尾巴只是无害的一行；运行结束时压缩一次。

结果以最多 32 条的有界批次落盘，异常退出后未提交的批次会在断点恢复时重跑。要恢复，首次运行和恢复运行都传入同一个 `--run-id` 与 `--run-identity`（绑定适配器和被测产物的稳定身份），恢复时再加 `--resume`；数据集内容、版本身份或执行配置任一不一致都会被拒绝，而不是把两次实验混进同一份报告。

同一次运行在每个 case 上都会计算内容哈希并记录身份清单，因此恢复时能明确区分"新增 case"和"case 内容被改过"。

跨进程互斥用文件锁实现：同一条运行或同一个演化循环在同一时刻只能有一个持有者，第二个持有者会立即失败而不是排队。`evolve-auto` 的检查点（`.assay/workspaces/<loop-id>/checkpoint.json`）原子写入，异常、时间预算或调用预算中止后，可以提高预算或修复外部故障再用同样的参数续跑。

存储层只有 `store.Results` 一个接口，引擎只依赖它：想换成 JDBC / SQLite 或对象存储，另写一个实现即可，调用方不用改。

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

策略文件可以约束 regression、holdout 和数值目标。目标可直接使用 `hard_pass`、`soft_warning_count`、`latency_ms`、`steps`、`llm_calls`、`input_tokens`、`output_tokens`、`total_tokens` 和 `cost_usd`，也可以从标准结果路径读取业务字段；支持 `mean`、`sum`、`min`、`max` 聚合，以及最大允许退化和最低改善幅度。

`scenarioGates` 可以为高风险或小样本场景单独设置最低样本数、最低通过率和允许退化，避免被全量平均值掩盖。

决策含义：

- `accept` —— 目标问题获得可测量改善，且保护集没有超过策略允许的退化。
- `reject` —— 候选没有达到声明的改善目标。
- `rollback` —— 候选破坏 regression、holdout、版本身份或必要指标。

每次演化保存完整的基线与候选运行结果、`evolution.json`、`evolution_report.md` 和存储中的审计记录。

### 文本型自动演化

自动演化适配器实现 `AutoEvolutionAdapterFactory`，提供基线文本产物、诊断器、候选生成器和"按产物构建运行适配器"的函数。

```bash
java -jar target/assay.jar evolve-auto \
  --auto-adapter com.example.MyAutoEvolution \
  --policy examples/evolution.policy.json \
  --improvement improvement.jsonl --regression regression.jsonl --holdout holdout.jsonl \
  --max-rounds 1 --max-candidates-per-round 2 --max-elapsed-seconds 300 --max-evolver-calls 4
```

`TextArtifactWorkspace` 在 `.assay/workspaces` 中保存基线快照和候选产物，领域项目的原文件不被触碰。基线可以是单个 UTF-8 文本文件，也可以是文本目录。

多文件候选有两种表达方式：用 `TextCandidate.files` 给出完整内容，或用 `TextCandidate.operations` 执行受限的 `write`、`delete`、`move`；路径必须是用 `/` 的相对文件路径。越界或冲突的文件操作会在复制候选之前被拒绝，回滚候选不会覆盖基线目录。候选落盘前会比对内容哈希，因此中断后重新落盘同一个候选是幂等的。

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
- **运行完整性与恢复** —— 空测试集与重复 case id 在建运行前就被拒绝；数据集、版本身份或执行配置变化会拒绝复用；批次写入被打断后重放未提交的那一批；重复 trace id 落为硬失败且不重复记数。
- **并发** —— 跨进程文件锁（测试会真的启动第二个 JVM 争抢同一把锁）、两个进程并发写不同运行不丢数据、适配器的并发上限。
- **演化** —— accept / reject / rollback 三类决策、场景门禁、目标缺值时的处理。
- **自动演化** —— 时间与调用预算、可重试失败重试一次、破坏性候选回退、检查点续跑复用已落盘候选。
- **进程与容器** —— 超时终止整棵进程树（断言孙进程确实消失）、输出上限与截断标记、容器命令的安全参数。
- **测试集选择** —— 确定性规则的最低档位、AI 只能提高不能降低、本地/远程输入边界。
- **范围审计** —— 发现分类与行号侧别、模型发现的校验与处置轨迹、报告产物。
- **OpenAI Trace** —— span 类型识别的各种写法、token 与错误聚合、按 trace id 分组与非法输入拒绝。
- **其余** —— JSON 编解码、评测指标、审核样本晋级、命令行退出码（`0` / `1` / `2`）。

## 扩展点

- **更强的隔离**：把候选执行换成容器 Runner，框架侧不用改。
- **换存储**：实现 `Results`，`--store` 指向新实现即可。
- **换 LLM 供应商**：`OpenAiCompatibleReviewer` 走 OpenAI 兼容协议；换协议时实现 `LlmReviewer` 与 `TestSelectionService.JsonReviewer`。
- **接更多 Trace 来源**：`NormalizedTrace` 是稳定契约，任何能产出它的采集方式都可以接进来。
