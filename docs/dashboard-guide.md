# Assay 界面使用指南（大白话版）

Assay 可以理解成一个 **AI Agent 的质检站**：你给它一批测试题，它让 Agent 跑一遍，检查回答、工具调用、延迟和成本，最后给出一份体检报告，帮你决定“能不能发布”。

不需要懂 Java，也不需要先写代码。先跑内置示例，再接入自己的 Agent。

## 最快上手

```bash
java -jar app/target/assay.jar demo
```

这条命令会：

1. 用内置 EchoAdapter 跑一条示例评测（不需要 API Key）；
2. 启动 Dashboard，并启用任务队列；
3. 自动打开浏览器。

Windows 也可以双击 `assay.cmd`，macOS / Linux 运行 `sh ./assay`。

> 如果还没有 `app/target/assay.jar`，先在项目根目录运行 `mvn -q -DskipTests package`。

---

## 1. 总览

**一句话：进门先看的总仪表盘。**

- 一共跑了多少次、通过率多少、失败多少
- 最近跑了哪些测试
- 最近 7 天通过率趋势
- 每个测试集健不健康

顶部还有一张可折叠的「三步开始」卡片：第一次使用时默认展开，直接告诉你怎么跑示例、怎么看报告、怎么接入自己的 Agent。

## 2. 开始评测

**一句话：给不想写代码的人用的“测我的 Agent”向导。**

你只需要告诉它三件事：

1. 你的 Agent 接口地址
2. 它的回答在返回 JSON 的哪个字段里（例如 `data.answer`）
3. 回答里应该出现什么关键词（例如 `hello`）

然后页面会：

- 生成 `my-agent.http.json` 配置
- 生成 `http-cases.jsonl` 测试用例模板
- 给出可直接复制的运行命令

复制命令到终端运行，回到界面点「刷新结果」即可看到报告。

## 3. 任务队列

**一句话：像取号排队，提交完不用守着。**

你把评测任务丢进队列，后台 worker 会一个个取出来跑。

- 看排队中 / 运行中 / 已完成
- 取消还没跑完的任务
- 失败任务按最大次数重试

这个页面需要启动时带 `--worker`。`demo` 命令已经默认启用 worker。

## 4. 运行记录

**一句话：所有体检报告的档案柜。**

点开一份报告，顶部先给出一张**结论卡**：通过 / 通过但有复核信号 / 未通过，并列出首个失败用例、被拒工具、subagent 交接、最长重复步骤等关键发现。

下面有四个固定页签，报告含 `multi_agent` 时还会多一个「多智能体」：

- **用例与 Trace**：每道题过没过，Agent 每一步干了什么（像行车记录仪）
- **场景分布**：不同类型题目的表现，通过率带进度条
- **规则统计**：每条检查规则的通过率，也带进度条和跨 run 趋势
- **多智能体**：总步骤、模型/工具调用、Agent 交接、被拒工具、最长重复步骤和参与 Agent
- **原始数据**：完整 JSON，方便排查

重点看“硬失败”的用例，以及它是在第几步开始出错的。

## 5. 版本对比

**一句话：拿旧版本和新版本的两份体检报告对比。**

适合场景：同一批测试题，旧版本跑一次、新版本跑一次，然后问“新版本有没有变差？”

页面会显示：

- 总体结论：`PASS`（可以）/ `WARNING`（有提醒）/ `BLOCK`（拦下）
- 通过率、延迟、Token、成本的变化
- 哪些用例从“过”变成“挂”
- 第一个出错的是哪一步
- 哪条规则变差了

生成示例：

```bash
java -jar app/target/assay.jar compare \
  --baseline <旧 run_id> --candidate <新 run_id> \
  --store .assay/store --output .assay/runs/compare-demo
```

## 6. 版本演化

**一句话：给一个“候选版本”做更严格的三组考试。**

它同时看三组题：

- **improvement（改善集）**：新版本该变好的地方
- **regression（回归集）**：老功能不能被弄坏
- **holdout（留出集）**：没用调参的题，防止“死记硬背”

最后给结论：`accept`（可以采用）/ `reject`（不采用）/ `rollback`（退回旧版）。

生成示例：

```bash
java -jar app/target/assay.jar evolve \
  --baseline-adapter 'io.assay.examples.IntentRouterAdapters$Baseline' \
  --candidate-adapter 'io.assay.examples.IntentRouterAdapters$Candidate' \
  --candidate examples/evolution.candidate.json \
  --policy examples/evolution.policy.json \
  --improvement examples/evolution.improvement.jsonl \
  --regression examples/evolution.regression.jsonl \
  --holdout examples/evolution.holdout.jsonl \
  --store .assay/store --output .assay/runs --experiment-id demo-evolution
```

## 7. 自动演化

**一句话：让机器自己“试配方”，一轮轮改到满意为止。**

流程：

1. 诊断当前版本哪里不行
2. 自动生成几个候选改法（改 prompt、改规则、改文本文件）
3. 在沙箱里试着改
4. 拿三组题分别考试
5. 行就采用，不行就扔掉或回退
6. 继续下一轮，直到用完轮数 / 时间 / 调用次数

它**不会动生产代码**，只在沙箱里试。适合“没有现成候选，让系统自动试几版看看”。

生成示例：

```bash
java -jar app/target/assay.jar evolve-auto \
  --auto-adapter io.assay.examples.IntentRouterAutoEvolution \
  --policy examples/evolution.policy.json \
  --improvement examples/evolution.improvement.jsonl \
  --regression examples/evolution.regression.jsonl \
  --holdout examples/evolution.holdout.jsonl \
  --store .assay/store --output .assay/runs --workspace .assay/workspaces \
  --max-rounds 1 --max-candidates-per-round 2 --loop-id demo-auto
```

## 8. 范围审计

**一句话：检查 AI 改代码有没有“越界”。**

给它一个任务说明和一段 Git 改动，它检查：

- 有没有改任务没要求的文件
- 有没有顺手加无关功能
- 有没有碰到禁止修改的路径
- 改动有没有证据支撑

结论：明确越界 / 疑似越界 / 证据不足 / 没发现明显问题。

```bash
java -jar app/target/assay.jar audit-scope \
  --repository . --base HEAD~1 --target HEAD \
  --spec task-spec.json --output .assay/audits/demo
```

## 9. 发布验证

**一句话：出厂前的流水线质检。**

按顺序跑：

1. **regression**：先保证老功能没坏
2. **smoke**：再跑关键冒烟
3. **full**：可选的全量

哪一步硬门禁失败，后面就不跑了，直接告诉你这次发布不安全。

## 顶部常用操作

- **搜索**：按名字、路径、状态过滤报告
- **刷新结果**：重新扫描报告目录
- **导入 JSON**：不放进 store，直接拖一份报告进来看
- **主题**：浅色纸面 / 深色夜纸

## 几个常见词

| 词 | 大白话 |
|---|---|
| 硬门禁 | 必须过，不过就不能发布 |
| 软告警 | 提醒你注意，但不一定拦发布 |
| Trace | Agent 的执行录像，能看到每一步 |
| 首错 | 整条链路里第一个出错的地方 |
| accept / reject / rollback | 采用 / 不采用 / 退回旧版 |
| 任务队列 | 提交后由后台 worker 排队执行 |

## 三者怎么选

| 功能 | 输入 | 输出 | 什么时候用 |
|---|---|---|---|
| 版本对比 | 两次已存 run | PASS / WARNING / BLOCK | 同一批用例，发布前后快速门禁 |
| 版本演化 | 基线/候选 adapter + 三组数据集 | accept / reject / rollback | 已知候选版本的综合验收 |
| 自动演化 | 自动演化 adapter + 三组数据集 | 自动生成并评测候选，推进版本 | 没有现成候选，让系统自动试错 |
