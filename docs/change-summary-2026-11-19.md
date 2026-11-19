# 变更清单 · 2026-11-19

> 范围：Assay Dashboard、HTTP 直提队列、存储分层、报告/队列/引擎拆分、ArchUnit 分层规则。
>
> 说明：工作区在本次之前已有未提交改动；本文只列本轮相关工作。

## 一、目标

1. **前端更直观**：主导航收敛，结果页先给结论，增加柱状图、环形图、折线图。
2. **操作更简单**：开始评测可以直接提交到 durable job queue。
3. **结构更清晰**：`runtime` 不再依赖具体 store；拆掉三个大类；把分层约定变成可执行测试。

## 二、Dashboard / 前端

### 新增

- `assay-dashboard/src/main/resources/io/assay/web/charts.js`
- `assay-dashboard/src/main/resources/io/assay/web/charts.css`
- `assay-dashboard/src/main/resources/io/assay/web/dashboard-model.js`

### 修改

- `app.js`
- `index.html`
- `DashboardServer.java`
- `DashboardServerTest.java`
- `dashboard.test.cjs`

### 内容

- 主导航收敛为 **总览 / 开始评测 / 运行记录 / 版本对比**，其余功能收进「更多」。
- 总览：运行状态环形图、测试集健康柱状图、7 天通过率折线图。
- 运行详情：默认「概览」页签，包含结论卡、KPI、用例状态环形图、规则/场景通过率柱状图、失败原因柱状图、优先处理用例表。
- 版本对比：门禁结论卡、用例状态迁移堆叠图、场景通过率对比分组柱状图。
- 版本演化：decision 结论卡、三组数据通过率对比、回归用例数柱状图。
- 自动演化：候选决策环形图、每轮决策柱状图、每轮最佳候选通过率、轮次趋势折线图。
- 范围审计：发现状态分布、风险分布柱状图。
- 发布验证：阶段通过率柱状图、阶段通过/硬失败柱状图。
- `dashboard-model.js` 提供 `runView` / `overviewView` / `compareView` / `autoView` / `releaseView`，页面只负责渲染。
- Node 测试从 17 项增加到 **26 项**。

## 三、HTTP 直提队列

- `DashboardServer` 新增 `POST /api/setup/jobs`。
- 接收 HTTP adapter 配置和 JSONL 用例文本，写入报告目录下的 `.dashboard-setup-*` 临时目录后提交 job。
- 校验 `config.type=http`、`endpoint`、`output_path`、非空 `hard_gates`，以及每行用例的 `id/case_id`。
- 仅当 `serve --worker` 时可用，仍要求 `X-Assay-Request: 1`，并且只写报告目录内部。
- 开始评测页 HTTP 模式新增「直接提交到队列」；没有 worker 时仍可下载配置走 CLI。
- `DashboardServerTest` 新增成功提交和非法配置/非法用例测试。

## 四、模块与分层

### 新增模块

```text
assay-bootstrap/
└─ io.assay.bootstrap.Stores
```

- `runtime` 不再依赖 `assay-store-file` / `assay-store-jdbc`，只依赖 core 的 `Results` / `JobStore` 接口。
- CLI / Server 通过 `assay-bootstrap` 打开具体 store。
- 根 `pom.xml`、`dependencyManagement`、Dockerfile、README、改进计划同步更新。

### ArchUnit

- 新增 `assay-integration-tests/src/test/java/io/assay/architecture/LayeringRulesTest.java`。
- 9 条规则覆盖 core / runtime / stores / bootstrap / dashboard / synth / examples / CLI。
- 没有新增独立测试模块（按要求）。

## 五、大类拆分

| 原类 | 拆分后 |
|---|---|
| `EvaluationEngine` 961 行 | `EvaluationEngine` 249 + `RunOrchestrator` 401 + `CaseEvaluator` 441 + `RunIdentity` 73 |
| `Reporting` 898 行 | `Reporting` 33 + `RunReportWriter` 391 + `EvolutionReportWriter` 410 + `AutoEvolutionReportWriter` 68 + `ReportSupport` 100 |
| `QueueCommands` 627 行 | `QueueCommands` 64 + `QueueServeCommand` 110 + `QueueSubmitCommand` 91 + `QueueWorkerCommand` 229 + `QueueAdminCommands` 187 + `QueueSupport` 81 |

公共 API、CLI 调用方和现有测试保持不变。

## 六、构建修正

- Dashboard 静态资源在只改 JS/CSS 时可能留在旧 shaded jar 中。
- 根因是 `maven-jar-plugin` 没有强制重建 thin jar，导致上一次的 shaded jar 被再次喂给 shade。
- 已在 `app/pom.xml` 给 `maven-jar-plugin` 加 `forceCreation=true`。
- 验证：不执行 `clean`，只改 `app.js` 后重新 `package`，`app/target/assay.jar` 中能看到最新内容。

## 七、验证结果

| 项目 | 结果 |
|---|---|
| `mvn -B -ntp clean verify` | 16 个模块全部 SUCCESS |
| Java 测试 | 514 项，0 failure / 0 error，1 skipped |
| 聚合覆盖率 | 83.7% 行 / 67.9% 分支 |
| Checkstyle | 0 |
| SpotBugs | 0 |
| Node 测试 | 26 项全过 |
| ArchUnit | 9 条规则全过 |
| Headless UI | 总览 / 开始评测 / 自动演化 / 发布验证，无 JS 报错 |
| Demo 冒烟 | `demo --no-serve` 通过 |

## 八、预览

本地预览数据在 `.assay/showcase`（gitignored），启动方式：

```bash
java -jar app/target/assay.jar serve --output .assay/showcase --port 8080
```

然后打开 <http://127.0.0.1:8080/>。

## 九、边界与后续

- HTTP 直提队列需要 `serve --worker`；只读预览下按钮不会真正提交。
- `.assay/showcase` 是本地演示数据，不进入仓库。
- ArchUnit 当前覆盖 core / runtime / stores / bootstrap / dashboard / synth / examples / CLI；server / extensions 按你的要求没有新增测试模块。
- 本轮没有提交 git，改动仍在工作区。
