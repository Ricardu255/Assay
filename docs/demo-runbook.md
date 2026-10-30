# Assay 演示 Runbook

用于面试、项目答辩或录屏。完整流程约 3 分钟，不需要 API Key，也不会产生外部费用。

## 1. 构建

```bash
mvn -q clean package
```

## 2. 跑基线版本

```bash
java -jar app/target/assay.jar run \
  --adapter 'io.assay.examples.IntentRouterAdapters$Candidate' \
  --suite holdout \
  --cases examples/evolution.holdout.jsonl \
  --store .assay/demo-store \
  --output .assay/demo-runs \
  --run-id demo-baseline
```

`Candidate` 会把含 `billing` 的请求路由到 `SUPPORT`，与用例期望一致，硬门禁通过。

## 3. 跑退化版本

```bash
java -jar app/target/assay.jar run \
  --adapter 'io.assay.examples.IntentRouterAdapters$Regressed' \
  --suite holdout \
  --cases examples/evolution.holdout.jsonl \
  --store .assay/demo-store \
  --output .assay/demo-runs \
  --run-id demo-candidate
```

`Regressed` 把同一个请求路由回 `GENERAL`。用例带 `critical: true`，所以这是一次关键任务回归。

## 4. 一条命令给出发布结论

```bash
java -jar app/target/assay.jar compare \
  --baseline demo-baseline \
  --candidate demo-candidate \
  --store .assay/demo-store \
  --profile strict \
  --output .assay/demo-runs/compare
```

`--output` 指向目录时会同时生成 `report.md` 和 `compare.json`，后者供 Dashboard 的「版本对比」页读取。

预期：

- 状态 `BLOCK`，退出码 1
- 硬门禁通过率从 100% 降到 0%
- 1 条关键任务回归
- 首错落在 `IntentRouter/route`，`fields.route` 从 `SUPPORT` 变为 `GENERAL`
- 场景切片显示 `support-routing` 的退化

## 5. 展示修复版本通过

```bash
java -jar app/target/assay.jar run \
  --adapter 'io.assay.examples.IntentRouterAdapters$Candidate' \
  --suite holdout \
  --cases examples/evolution.holdout.jsonl \
  --store .assay/demo-store \
  --output .assay/demo-runs \
  --run-id demo-fixed

java -jar app/target/assay.jar compare \
  --baseline demo-baseline \
  --candidate demo-fixed \
  --store .assay/demo-store \
  --profile strict
```

预期状态 `PASS`。`strict` 预设只包含可稳定测量的质量与延迟阈值；如果自定义配置加入 Token/成本阈值、而 Trace 没有上报这两项，报告会把它们标为 `INFO: unavailable`，不会因此把版本判成失败。

## 6. 打开 Dashboard

```bash
java -jar app/target/assay.jar serve --output .assay/demo-runs --open-browser
```

在运行页选择 `demo-candidate`，展示：

1. 失败用例与硬门禁规则
2. Trace 步骤卡片，定位第一个失败步骤
3. 场景统计中的 `support-routing`
4. 切换到「版本对比」，点击 `demo-baseline -> demo-candidate`，展示门禁结论、指标对比、场景切片和首错
5. 如果先跑过 `evolve`，再展示版本演化页的首错诊断与回退结论

## 面试中的一句话

> Assay 不重新调用 Agent，而是比较两次真实运行：先给出 `PASS / WARNING / BLOCK`，再按场景切片列出退化，最后把首错定位到具体模块、动作和字段。

## 演示边界

- 演示使用确定性适配器，结果每次一致。
- `compare` 读取已保存的 Trace，不产生新的模型调用。
- Token 和成本优先使用 Trace 上报值；缺失时只显示标注过的估算或 `INFO`，默认不参与阻断。
- 真实 Agent 通过 HTTP 配置或离线 Trace 接入，不需要写 Java。
