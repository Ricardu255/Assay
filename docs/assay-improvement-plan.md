# Assay 工程化改进方案（2026-10）

> 目标：让 Assay 从“功能完整的评测框架”变成“看起来和用起来都像工程系统”的项目，同时不破坏它最重要的优势：领域解耦、零运行时依赖、可移植、可审计。
>
> 适用场景：面试展示、团队内部落地、后续产品化评估。
>
> 原则：**核心保持小而深，集成放到外围；不为了关键词堆技术栈。**

## 实施状态（2026-10-20）

- [x] P0：新增 [engineering-deep-dive.md](engineering-deep-dive.md)，README 补充工程深挖入口和 JDBC/Starter 用法。
- [x] P1（存储）：新增 `JdbcResultStore`、`Stores.open`、`Results.RunLock` 抽象；CLI 支持 `--store jdbc:...`；新增 5 项 JDBC 存储测试 + 1 项 CLI JDBC 测试。
- [x] P2（集成）：新增 `extensions/assay-spring-boot-starter`，提供文件/JDBC 存储自动配置、`EvaluationEngine` 装配和 `EvaluationListener` 注入，4 项上下文测试通过。
- [x] P2（交付）：新增 `Dockerfile`、`.dockerignore`，CI 用根 reactor 的 `clean verify` 覆盖全部模块，并构建镜像。
- [x] P1（模块化，Phase 1）：根 reactor 包含 `app`（artifact `assay`）、Starter、Micrometer、OTel；根目录 `mvn test` 一次构建四个模块；enforcer 禁止 `app` 引入 Spring/Micrometer/OTel；`core/cli/store` 包级拆分留 Phase 2。
- [x] P2（可观测，Micrometer）：新增零依赖 `EvaluationListener` SPI 和 `extensions/assay-metrics-micrometer`，2 项测试通过。
- [x] P2（可观测，OTel）：新增 `extensions/assay-tracing-otel`，run/case/batch span 和失败 run 收口，2 项测试通过。
- [x] P2（可观测，Stage span）：新增 `ADAPTER` / `RULES` / `STORE` 阶段回调；adapter/rules 挂 case，store 挂 run；核心 Stage 测试 + OTel 父子断言通过。
- [x] P0（基准）：新增 [benchmark.md](benchmark.md)，1,000 条轻量用例、8/20 workers 的本地基线（约 1,100 cases/s，明确标注不代表生产容量）。

---

## 一、现状评估

### 1.1 已经具备的工程深度

- Java 17、Maven、JUnit 5；主代码约 1.29 万行，测试约 4,900 行。
- 212 项核心 Java 测试 + 14 项 Node 测试 + 4 项 Spring Boot Starter 测试 + 2 项 Micrometer 测试 + 2 项 OTel 测试；GitHub Actions Windows/Linux 双平台。
- `ProjectAdapter` 四能力契约，支持 HTTP、Java 适配器、离线归一化轨迹、OpenInference/OTLP。
- 四层评测、硬/软门禁、三数据集演化、场景/质量/目标门禁、规则通过率、P95。
- `TraceDiff` LCS 首错、用例状态迁移、失败聚类。
- 批式落盘、断点恢复、case 内容哈希、跨进程文件锁、半行 JSON/UTF-8 尾部修复。
- 适配器 jar 内存加载、路径逃逸防护、进程树终止、容器隔离。
- 只读 Dashboard、主题、键盘导航、URL 状态恢复。

### 1.2 容易被误判为“玩具”的点

| 表面信号 | 面试官可能的误读 | 实际情况 |
|---|---|---|
| 纯 Java、零依赖 | “没用过 Spring/中间件” | 刻意选择，换取单 jar、离线、可审计；但外围缺少集成证明 |
| 多模块只完成 Phase 1 | “没有真正拆包” | 根 reactor 已建；`core/cli/store` 包级拆分留 Phase 2 |
| JDBC 只有基础实现 | “没做过迁移/分布式锁” | `JdbcResultStore` 已落地；缺 schema migration、分布式锁 TTL |
| 手写 JSON/HTTP Server | “重复造轮子” | 零依赖约束下的取舍，有测试覆盖；但需要把“为什么”讲清楚 |
| 没有内置 exporter / LLM span | “没做过可观测性” | Micrometer/OTel 和 adapter/rules/store stage span 已落地；exporter 由使用方提供，LLM span 未做 |
| 没有 10k/真实 Agent 压测 | “没验证过规模” | 1,000 条轻量基线；缺 10k/JDBC/真实 Agent 数据 |
| Starter 已落地 | “不能嵌入企业应用” | 可选模块，不污染核心；发布到 Maven Central 仍需配置 |

**根因不是代码简单，是工程信号藏得太深。** README 先讲功能，没有先讲失败处理、取舍和验证；面试官看不到 1.3 万行里的难点。

---

## 二、改进原则

1. **核心零依赖不破**：`assay-core` 不引入 Spring、JDBC、OTel、Jackson。
2. **集成放外围**：JDBC、Spring Boot Starter、Micrometer/OTel、Docker 都是可选模块。
3. **每个改动有验收标准**：不是“加了依赖”，而是“证明了一个能力”。
4. **不做关键词堆砌**：没有真实场景的 Kafka/Redis/K8s 不加。
5. **先证据，后功能**：先把失败矩阵、benchmark、架构图做出来，再考虑扩功能。
6. **保持可逆**：模块可拆、存储可换、适配器可替换；任何集成都能被移除。

### 非目标

- 不把 Assay 改成 Agent 运行时。
- 不重写成 Python。
- 不为了“看起来复杂”引入分布式全家桶。
- 不在核心中接入 AgentScope/ADK；如需要，做独立适配器。
- 不承诺“生产级多租户/权限”，除非真的要做平台。

---

## 三、优先级总览

| 优先级 | 事项 | 价值 | 预计投入 | 依赖 |
|---|---|---|---|---|
| **P0** | 工程叙事与证据：README 重构、架构图、failure matrix、benchmark | 最高，立刻改变“玩具”观感 | 3～5 天 | 无 |
| **P1** | 多模块拆分（core / cli / store-file / store-jdbc） | 证明模块化与边界 | 3～5 天 | P0 的文档 |
| **P1** | JDBC 存储实现 + 契约测试 | 证明存储抽象、事务、迁移 | 4～6 天 | 多模块 |
| **P2** | Spring Boot Starter | 证明企业集成能力 | 2～3 天 | 多模块 |
| **P2** | Micrometer/OTel + 压测 | 证明可观测性与规模 | 3～5 天 | Starter/独立模块 |
| **P2** | Dockerfile + Release + CI 增强 | 证明交付能力 | 1～2 天 | 无 |
| **P3** | 评测深度：工具链序列规则、数据集版本、评估器校准 | 加深核心护城河 | 5～8 天 | 无 |
| **P3** | 外部 Agent 适配器（AgentScope/ADK，可选） | 证明真实运行时接入 | 3～5 天/个 | 有真实需求 |

> 建议顺序：P0 → P1（多模块）→ P1（JDBC）→ P2（Starter）→ P2（OTel/压测）→ P2（交付）→ P3。
> 如果时间有限，只做 **P0 + 多模块 + JDBC**，已经能显著改变技术观感。

---

## 四、详细方案

### 4.1 P0：工程叙事与证据

#### 4.1.1 README 重构

把 README 的前 60 行从“功能列表”改成“工程问题 → 方案 → 证据”：

```text
1. 一句话定位
2. 它解决什么问题（发布决策，不是观测）
3. 架构图（适配器 → 评测 → 演化 → 诊断 → 存储/报告）
4. 三个最难的问题（恢复 / 首错 / 隔离）
5. 关键数字（212 tests、13k 行、双平台 CI）
6. 快速开始
7. 设计取舍（为什么零依赖、为什么文件存储、为什么 LLM 不进硬门禁）
8. 生产化路线图（JDBC、Starter、OTel）
```

#### 4.1.2 `docs/engineering-deep-dive.md`

固定写三章，每章 1～2 页：

1. **崩溃恢复**：批次、manifest、尾部修复、文件锁、at-least-once 语义。
2. **首错诊断**：LCS 对齐、分类、忽略字段、300 步上限、边界案例。
3. **隔离与安全**：路径逃逸、staging/manifest/哈希、进程树、容器参数、Dashboard 路径校验。

每章必须包含：问题、方案、失败矩阵、测试、取舍。

#### 4.1.3 Failure Matrix

在文档里放一张表：

| 故障 | 行为 | 恢复/测试 |
|---|---|---|
| 批写入前崩溃 | 未提交批次丢失 | resume 重跑 |
| 写入中崩溃 | 最后一行半行 | 截断尾部 |
| UTF-8 半字符 | 解码失败在末行 | 截断尾部 |
| 中间行损坏 | 明确报错 | 不自动修复 |
| 两进程同 run | 第二个失败 | 文件锁 |
| resume case 变化 | manifest 不匹配 | 拒绝恢复 |
| 重复 trace id | 硬失败 | 后置检查 |

#### 4.1.4 Benchmark

- 新增 `assay-benchmarks` 模块（JMH 或简单 harness）。
- 场景：1,000 / 10,000 条合成 case，1/20/50 workers；适配器模拟 5～20ms 延迟。
- 输出：吞吐（cases/s）、P50/P95/P99、恢复耗时、存储写入耗时。
- 结果写进 README 和 `docs/benchmark.md`。
- **不要**在 CI 跑全量 benchmark；只跑 100 条 smoke，防止退化。

**验收**：

- 新读者 5 分钟内能说出“这个项目最难的三件事”。
- README 首屏有架构图和关键数字。
- failure matrix 覆盖至少 7 种故障。
- benchmark 有可复现命令和结果表。

---

### 4.2 P1：多模块拆分

#### 4.2.1 目标结构

Phase 1 已落地，先保证“一个 reactor 能构建全部模块”，不急着拆包：

```text
assay-parent/
├── pom.xml                     # parent + dependencyManagement
├── app/                        # artifact: assay（当前仍包含 core/cli/dashboard/store）
│   ├── pom.xml
│   └── src/
└── extensions/
    ├── assay-spring-boot-starter/
    └── assay-metrics-micrometer/
```

Phase 2 再把 `app/src` 拆成更细的模块：

```text
assay-core/                     # model, rules, engine, evolve, store API, report, json, support
assay-cli/                      # AssayCli, CommandLine
assay-store-file/               # ResultStore
assay-store-jdbc/               # JdbcResultStore
assay-dashboard/                # web + resources
```

后续可再拆：

```text
assay-adapter-http/
assay-adapter-agentscope/       # 仅在有真实需求时
assay-spring-boot-starter/
assay-benchmarks/
assay-metrics-micrometer/
```

#### 4.2.2 依赖规则

- `assay-core` 只依赖 JDK + JUnit（test）。
- `assay-store-file` 依赖 `assay-core`。
- `assay-store-jdbc` 依赖 `assay-core` + JDBC 驱动（test 用 H2）。
- `assay-cli` 依赖 core + store-file + dashboard + adapter-http。
- `assay-spring-boot-starter` 依赖 core + Spring Boot autoconfigure。
- 用 `maven-enforcer-plugin` 禁止 core 引入 Spring/JDBC/OTel。

#### 4.2.3 迁移步骤

Phase 1（已完成）：

1. 根 `pom.xml` 改为 parent，统一 Java 17、JUnit、Spring Boot、Micrometer 版本。
2. 主项目移入 `app/`，artifactId 保持 `assay`；根目录的 `assay` 启动脚本不冲突。
3. Starter 和 Micrometer 扩展现有 POM 加 `<parent>`，由 reactor 统一构建。
4. `mvn test` 一次跑 212 + 4 + 2 + 2 项测试。

Phase 2（待做）：

1. 先把 `io.assay.store.ResultStore` 移到 `assay-store-file`，core 只保留 `Results` 接口。
2. 把 `AssayCli` 和 `examples` 移到 `assay-cli`。
3. 把 `web` 和 `resources` 移到 `assay-dashboard`。
4. 每移一步跑全量测试，保持 `mvn test` 绿。
5. 最后加 enforcer 规则，防止反向依赖。

**验收**：

- `mvn -q test` 仍然全绿。
- `mvn -pl assay-core dependency:tree` 只有 JDK。
- `assay-cli` 的 jar 仍可独立运行。
- README 的构建命令更新为 `mvn -q clean package`。

---

### 4.3 P1：JDBC 存储

#### 4.3.1 目标

证明 `Results` 接口不是“为文件存储定制的”，而是可替换的存储契约。

#### 4.3.2 Schema 草案

```sql
create table assay_run (
  run_id varchar(128) primary key,
  adapter varchar(256) not null,
  suite varchar(128) not null,
  source varchar(32) not null,
  status varchar(32) not null,
  started_at timestamp not null,
  finished_at timestamp,
  metadata_json clob not null
);

create table assay_case (
  run_id varchar(128) not null,
  case_id varchar(256) not null,
  payload_json clob not null,
  hard_pass boolean not null,
  soft_warning_count int not null,
  updated_at timestamp not null,
  primary key (run_id, case_id)
);

create table assay_case_manifest (
  run_id varchar(128) not null,
  case_id varchar(256) not null,
  content_sha256 char(64) not null,
  primary key (run_id, case_id)
);

create table assay_review (
  id bigint generated always as identity primary key,
  run_id varchar(128) not null,
  case_id varchar(256) not null,
  decision varchar(64) not null,
  final_conclusion clob not null,
  reviewed_at timestamp not null
);

create table assay_evolution (
  experiment_id varchar(128) primary key,
  candidate_id varchar(256) not null,
  decision varchar(32) not null,
  result_json clob not null,
  created_at timestamp not null
);
```

#### 4.3.3 语义要求

- `saveCases` 必须在一个事务里批量 upsert；失败整体回滚。
- `finishRun` 更新状态；不删除 case。
- `startRun(resume=true)` 必须校验 adapter/suite/source/metadata + manifest。
- `lockRun` 用数据库 advisory lock / `select for update`，或者复用文件锁（单机）。
- `listResults` 按 case_id 排序，和文件实现一致。
- `addCaseCheck` 必须原子地更新 checks、hard_pass、soft_warning_count。
- 所有 JSON 字段用 UTF-8；大字段用 `clob/text`。
- 迁移用 `schema.sql` + 版本表，避免引入 Flyway 依赖；如引入 Flyway，仅限 JDBC 模块。

#### 4.3.4 契约测试

把现有 `StoreRecoveryTest` / `RunIntegrityTest` 抽成 **存储契约测试套件**，同一套测试跑：

- `FileStoreContractTest`
- `JdbcStoreContractTest`（H2）
- 可选：PostgreSQL（Testcontainers 或 CI service）

**验收**：

- `mvn -pl assay-store-jdbc test` 全绿。
- 同一套契约测试在两个实现上通过。
- `assay run --store jdbc:...` 能跑通，报告和恢复行为一致。
- 核心模块没有 JDBC 依赖。

---

### 4.4 P2：Spring Boot Starter

#### 4.4.1 目标

证明 Assay 能嵌入 Spring 应用，而不是只能 CLI。

#### 4.4.2 内容

- `assay-spring-boot-starter` 模块。
- `@AutoConfiguration` + `@ConditionalOnMissingBean`。
- `application.yml`：

```yaml
assay:
  store:
    type: jdbc        # file | jdbc
    url: jdbc:postgresql://...
  output: .assay/runs
  workers: 8
  timeout-seconds: 30
  dashboard:
    enabled: true
```

- 自动配置：`Results`、`EvaluationEngine`、`DashboardServer`（可选）。
- Actuator：`/actuator/assay` 健康检查（store 可读、最近 run 状态）。
- 测试：`ApplicationContextRunner` 验证条件装配。

**验收**：

- 一个最小 Spring Boot 示例能启动并跑一条 case。
- 不引入 Starter 时，核心仍然是零依赖。
- `mvn -q -pl :assay-spring-boot-starter -am test` 全绿。

---

### 4.5 P2：可观测性与压测

#### 4.5.1 指标

在 core 中加一个零依赖的 `EvaluationListener` SPI：

```java
public interface EvaluationListener {
    void onCaseStarted(EvalCase c);
    void onCaseFinished(CaseResult r);
    void onBatchSaved(int count, long durationNanos);
    void onRunFinished(Map<String, Object> summary);
}
```

Micrometer/OTel 实现放外围模块，不污染 core。

指标建议：

- `assay_cases_total{status}` 
- `assay_case_duration_seconds`
- `assay_store_append_seconds`
- `assay_store_recovered_total`
- `assay_evolve_decision_total{decision}`
- `assay_trace_diff_divergences`
- `assay_eval_workers`

#### 4.5.2 OTel Trace

- 已实现 run/case/batch span；case 挂 run，失败 run 会关闭 span。
- adapter / rules / store 的 stage 子 span 已实现；LLM review span 尚未实现。
- exporter 由使用方提供；不要求核心依赖 OTel；只在 `assay-tracing-otel` 模块。

#### 4.5.3 压测

- 1,000 / 10,000 case；1/20/50 workers。
- 报告：吞吐、P50/P95/P99、错误率、恢复时间。
- 对比 file vs JDBC（JDBC 小数据可能更慢，要大方的写出来）。

**验收**：

- 一条命令生成 benchmark 报告。
- README 有结果表和环境说明。
- CI 跑 100 条 smoke，不跑全量。

---

### 4.6 P2：交付与 CI

- 多阶段 Dockerfile：Maven builder + JRE 17 runtime，非 root，只读根文件系统，挂载 `.assay`。
- GitHub Release：jar + sha256 + 启动脚本。
- CI 增加：
  - `mvn -q clean verify`
  - Node Dashboard tests
  - JDBC 契约测试（H2）
  - Starter 上下文测试
  - 100 条 benchmark smoke
  - Docker build（可选）
- 保持 CI 时间在 10 分钟以内。

**验收**：

- `docker run` 能跑一次 demo。
- Release 有校验和。
- CI 在 Windows/Linux 都绿。

---

### 4.7 P3：评测深度

按优先级：

1. **工具链序列规则**：`sequence_eq` 或适配器暴露 `fields.tool_chain`；支持“工具序列 + 参数”断言。
2. **数据集版本管理**：dataset name/version/hash 进入 run metadata 和报告；恢复时校验。
3. **评估器校准**：`EvaluatorMetrics` 已有 agreement/FP/FN/stability；加校准报告（人工标签 vs 自动判定）。
4. **统计置信**：场景已有 Wilson；可加 delta 的 bootstrap 区间，但不要过度承诺显著性。
5. **Badcase 回流**：review → export → promote-review 已经存在；补一个 dashboard 视图或 CLI 汇总。

**验收**：

- 每条新规则有单测和边界用例。
- 数据集版本出现在 `results.json` / `report.md`。
- 校准报告能回答“评测器和人一致吗”。

---

### 4.8 P3：外部 Agent 适配器（可选）

只有在有真实需求时做：

- `assay-adapter-agentscope` 或 `assay-adapter-adk` 独立模块。
- `callAgent`：创建 Agent/Session，收集 events。
- `readTrace`：events → `TraceEvent` / `NormalizedTrace`。
- `target_version` 必须配置，保证 `evolve` 版本身份匹配。
- 不在核心引入 AgentScope/ADK 依赖。
- 如果只是偶尔评测，优先用 `trace_file` + 导出脚本，不做适配器。

---

## 五、路线图

### Phase 0：证据（1 周）

- README 重构
- `docs/engineering-deep-dive.md`
- failure matrix
- benchmark harness + 首轮数据

**产出**：面试官 5 分钟能看懂“难在哪”。

### Phase 1：模块与存储（2 周）

- parent POM + 4 模块
- `assay-store-jdbc`
- 存储契约测试
- enforcer 规则

**产出**：存储可替换、模块边界可验证。

### Phase 2：集成与可观测（2 周）

- Spring Boot Starter
- EvaluationListener + Micrometer/OTel
- Docker/Release/CI
- 压测报告

**产出**：能嵌入 Spring、能观测、有规模数字。

### Phase 3：评测深度（2～3 周）

- 工具链序列规则
- 数据集版本
- 评估器校准
- 可选外部适配器

**产出**：核心护城河更深，而不是技术栈更重。

---

## 六、验收清单（Definition of Done）

- [ ] `assay-core` 依赖树只有 JDK。
- [ ] `mvn -q clean verify` 全绿，测试数量只增不减。
- [ ] 文件存储与 JDBC 存储跑同一套契约测试。
- [ ] `run/evolve` 在两种存储上行为一致。
- [ ] Spring Boot Starter 能自动装配并跑一条 case。
- [ ] `/actuator/assay` 或等价健康检查可用。
- [ ] benchmark 有可复现命令和结果表。
- [ ] Docker 镜像可运行 demo。
- [ ] README 首屏有架构图、关键数字、三个工程难点。
- [ ] failure matrix 覆盖恢复、锁、路径、进程/容器。
- [ ] `docs/engineering-deep-dive.md` 讲清三个最难问题。
- [ ] 没有为了关键词引入无场景依赖。

---

## 七、风险与止损

| 风险 | 表现 | 止损 |
|---|---|---|
| 范围蔓延 | 模块越拆越多，功能越加越散 | 每阶段只做表中事项；P3 可砍 |
| 多模块复杂化 | 构建变慢、IDE 体验差 | 先拆 4 个，不一次拆细 |
| JDBC 语义偏差 | 恢复/锁/排序和文件实现不一致 | 契约测试；先 H2 再 Postgres |
| Spring 版本绑定 | Starter 绑死 Spring Boot 版本 | 只支持当前 LTS；核心不受影响 |
| benchmark 不稳定 | 数字波动大，无法复现 | 固定环境、固定数据、跑多次取中位数 |
| CI 变慢 | 超过 10 分钟 | benchmark 只跑 smoke；集成测试分 job |
| 文档漂移 | README 和代码不一致 | 每阶段更新文档；CI 检查链接/命令 |

---

## 八、对外表达

### 8.1 README 首屏

建议顺序：

1. 一句话：Agent 发布前的“化验台”。
2. 问题：demo 能跑 ≠ 能发布；观测 ≠ 决策。
3. 架构图。
4. 三个工程难点：恢复、首错、隔离。
5. 关键数字：212 tests、双平台 CI、13k 行、零依赖核心、JDBC/Starter/Micrometer/OTel/stage span 已落地。
6. 快速开始。
7. 设计取舍 + 生产化路线图。

### 8.2 简历 bullet（当前已落地版）

> Java 17 Maven reactor 的 Agent 评测与发布门禁引擎；app 核心零依赖，外围提供 JDBC 存储、Spring Boot Starter、Micrometer 指标与 OTel span；实现批式落盘/断点恢复/跨进程锁、LCS 轨迹首错、三数据集演化门禁；212 项测试 + 1,000 条轻量基准，CI 双平台。

### 8.3 面试三句话

- **定位**：Assay 做“评测、诊断、发布门禁”，不做 Agent 运行时。
- **取舍**：核心零依赖换可移植；企业集成放外围（JDBC/Starter/OTel）。
- **难点**：恢复、LCS 首错、隔离；不是 CRUD。

---

## 九、不建议做

- 不要为了关键词加 Kafka/Redis/K8s；没有真实场景，一问就露馅。
- 不要重写成 Python；Java 是差异化。
- 不要把 AgentScope/ADK 放进核心；需要时做独立适配器。
- 不要在没有 benchmark 的情况下宣称“生产级/高并发”。
- 不要让 `assay-core` 依赖 Spring/JDBC/OTel。
- 不要为了“模块多”而拆得过细；先 4 个模块，够用再拆。

---

## 十、一句话总结

**Assay 不是玩具，它只是把工程信号藏得太深。先补证据（P0），再把存储和集成做成可替换的外围（P1/P2），最后加深评测核心（P3）。这样既保留“零依赖、可移植、可审计”的优势，又能在面试和团队落地时证明：这是一个有边界、有取舍、有失败处理的工程系统。**
