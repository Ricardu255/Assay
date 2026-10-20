# Assay 工程深挖：恢复、首错与隔离

> 这份文档不是功能说明，而是回答三个问题：**崩溃后为什么能继续、退化为什么能定位、候选为什么不能乱跑。**
> 如果只看 README，容易把 Assay 当成一个规则评测脚本；这三块才是它作为工程系统的核心。

---

## 一、全景

```text
Case JSONL ──► EvaluationEngine ──► CaseResult / NormalizedTrace
                   │                        │
Adapter ───────────┘                        ├─► Results（文件 / JDBC）
（HTTP / Java / 离线 / OTLP）               ├─► Reporting
                                            └─► Dashboard

Baseline run ─┐
              ├─► EvolutionEngine ──► comparisons ──► accept / reject / rollback
Candidate run ┘        │                  │
                       │                  ├─ case_transitions + TraceDiff
                       │                  ├─ rule_deltas
                       │                  └─ scenario / quality / objectives
                       └─► evolution.json / evolution_report.md
```

三个不变量：

1. **已提交的结果不丢；未提交的批次可重跑。**
2. **诊断只读已经评测过的 Trace，不重跑 Agent。**
3. **候选执行有边界：路径、进程、容器、输出都有上限。**

---

## 二、崩溃恢复：从批次到文件锁

### 2.1 存储布局

```text
.assay/store/
├── runs/<run-id>/
│   ├── run.json          # 元数据、状态、配置
│   ├── manifest.json     # case_id -> 内容 SHA-256
│   └── cases.jsonl       # 追加写、last-write-wins
├── reviews/<run-id>.jsonl
├── evolutions/<experiment-id>.json
└── .locks/<run-id>.lock
```

### 2.2 写入路径

1. `startRun` 新建或恢复 `run.json`。
2. `saveCases` 把一批 `CaseResult` 归一化，补 `hard_pass` / `soft_warning_count`，一次性追加。
3. `appendCases` 先修复上次中断的尾部，再追加；成功后更新缓存。
4. `finishRun` 按 case id 排序重写 `cases.jsonl` 做压缩，再写最终状态。
5. `addCaseCheck` 追加整条更新记录，后写覆盖。

批次上限 32 条。异常退出最多丢失当前未提交批次，恢复时重跑。

### 2.3 恢复校验

- run 身份：adapter、suite、source、metadata 必须一致；`case_count` 允许变化。
- case manifest：旧 manifest 有、新数据集没有 → 拒绝；同 case 哈希变化 → 拒绝；新增 case → 允许。
- legacy run 没有 manifest 但有已保存 case → 拒绝恢复。
- 数据集/版本身份/执行配置不一致 → 拒绝复用。

### 2.4 尾部修复

`cases.jsonl` 逐行读取，只允许**最后一行未完成**：

| 磁盘状态 | 行为 |
|---|---|
| 完整 JSON + `\n` | 正常读取 |
| 完整 JSON、缺 `\n` | 读取，下次追加前补 `\n` |
| 半行 JSON | 解析失败，记录有效长度；下次截断 |
| 半行 UTF-8 | 解码失败；同样只在末行容忍 |
| 中间行损坏 | 明确报错，不猜、不跳过 |
| 缺 `case.case_id` | 明确报错 |
| 重复 case | 后写覆盖，读取按 case id 排序 |

### 2.5 锁与语义

- 每个 run 一个 `.locks/<run-id>.lock`，一字节 `FileChannel.tryLock`。
- 第二个 JVM 拿不到锁直接失败，不排队。
- 已提交批次不丢；未提交批次在 resume 时重跑。
- **不保证 exactly-once**：有副作用的接口需要幂等，或用稳定的 `run_id`/`case_id` 去重。

### 2.6 故障矩阵

| 故障 | 结果 | 恢复 |
|---|---|---|
| 调用 Agent 后、写批次前崩溃 | 该批未提交 | resume 重跑 |
| 写 `cases.jsonl` 中崩溃 | 最后一行半行 | 截断尾部 |
| 写完一行缺 `\n` | 完整行 | 下次补分隔符 |
| UTF-8 字符写一半 | 解码失败在末行 | 截断尾部 |
| 中间行损坏 | 明确报错 | 人工检查 |
| 两进程同 run | 第二个失败 | 文件锁 |
| resume case 变化 | manifest 不匹配 | 拒绝恢复 |
| 重复 trace id | 硬失败 | 后置检查 |

### 2.7 测试

- `StoreRecoveryTest`：截断、半字符、缺换行、再次中断、中间损坏、失败追加。
- `RunIntegrityTest`：空 suite/重复 case、resume 变更、批次重放、版本身份、重复 trace id。
- `ConcurrencySafetyTest`：真实第二 JVM 抢锁、两进程写不同 run、并发上限。

### 2.8 JDBC 存储：同一套语义的第二个实现

`Results` 不是文件存储的别名：它有 `RunLock` 抽象、run/case/manifest/review/evolution 五类操作。`JdbcResultStore` 用同一套语义实现：

- `assay_run` / `assay_case` / `assay_case_manifest` / `assay_review` / `assay_evolution` / `assay_lock`。
- `saveCases` 批量 upsert 在一个事务里；失败整体回滚。
- `startRun(resume=true)` 校验 run 身份和 manifest；缺失、变更、legacy run 都拒绝。
- `addCaseCheck` 用 `SELECT ... FOR UPDATE` 读改写。
- `lockRun` 插入 `assay_lock` 行做 run 级互斥；进程崩溃会留下锁行，需要人工清理（已知边界，没有 TTL）。

取舍：核心仍零运行时依赖，JDBC driver 由使用方提供；schema 自动创建，没有迁移框架；`text/boolean/timestamp` 是便携子集，生产要按数据库类型和锁超时验证。

测试：`JdbcResultStoreTest` 5 项 + CLI JDBC 测试 1 项；实际用 H2 跑过完整 `run`。

### 2.9 可观测性：零依赖 SPI + 可选实现

核心只定义 `EvaluationListener`（run/case/batch 生命周期），默认 no-op，且引擎会吞掉 listener 异常——观测者不能改变评测结果。Micrometer 实现放在 `extensions/assay-metrics-micrometer`：

- `assay.cases`、`assay.case.duration`（suite/status 标签）
- `assay.batches`、`assay.batch.size`、`assay.store.append`
- `assay.runs`、`assay.run.duration`（suite/source/status 标签）

标签刻意不带 `run_id` / `case_id`，避免时间序列基数爆炸。Micrometer 依赖只在扩展模块，核心仍然零依赖；Starter 会注入可选的 `EvaluationListener` bean。

---

## 三、首错诊断：LCS + 字段证据

### 3.1 输入输出

输入：baseline/candidate 两份 `NormalizedTrace`。
输出：`first_divergence` + 后续 `divergences`，包含类别、步骤、字段路径、两侧值。

### 3.2 算法

1. 抽取步骤 `key = module + "\0" + action`。
2. 对 key 序列做 LCS，回溯锚点。
3. 锚点之间的缺口：同位置配对为替换/重排，剩余为新增/缺失。
4. 匹配步骤比较 `error → status → fields`。
5. 字段 diff：map key 并集、list 下标、标量 `ValueOps.equal`。
6. 没有步骤差异时，再比较 `final_output`、trace 级 `fields`。
7. 按候选步骤排序，取第一处。
8. 超过 300 步退化为位置比较；字段 diff 最多 12 条。

### 3.3 为什么不用逐位置

前面插入一步会让后面全部错位，首错会误报在第一步。LCS 把增删限制在局部，代价是 O(n×m)，所以有 300 步上限。

### 3.4 噪声控制

默认忽略 `span_id`、`parent_span_id`、时间、耗时字段；用例可用 `metadata.trace_diff_ignore` 追加。耗时问题由指标/门禁负责，不由诊断负责。

### 3.5 边界

- 两侧无 Trace：`missing_trace`
- 步骤一致、答案变化：`final_output`
- 都一致：`no_visible_divergence`
- 步骤报错优先于字段变化
- 重复步骤/极端轨迹：确定性 tie-break；超限退化

### 3.6 定位

诊断是“第一处可见差异”，不是因果根因；它进入报告和人工复核，不直接改变发布判定。

---

## 四、门禁：从规则到发布决策

### 4.1 分层

| 层级 | 作用 |
|---|---|
| 用例级硬门禁 | Trace 完整、关键路由、安全规则 |
| 用例级软质量 | 长度、风格、疑似模块 |
| 场景门禁 | 防止小切片被平均值掩盖 |
| 绝对质量门禁 | 最低通过率、最大回归数、最大关键回归、最小样本 |
| 数值目标 | 延迟、Token、成本、业务字段 |
| 演化决策 | accept / reject / rollback |

### 4.2 决策顺序

1. 版本身份不匹配 → rollback
2. regression：候选硬失败、通过率下降、软告警增加 → rollback
3. holdout：硬失败、跌幅、软告警 → rollback
4. scenario gates → rollback
5. quality gates → rollback
6. objectives：缺失/退化/绝对上限 → rollback；未改善 → reject
7. 没有 rollback/reject，且满足改善要求 → accept

### 4.3 隐藏回归

baseline 通过 A-E，candidate 通过 A-D+F，通过率都是 50%，但 `regressed = [E]`、`recovered = [F]`。`maximum_task_regressions = 0` 直接 rollback；逐用例清单告诉人“退化的是 E”。

### 4.4 阈值

从基线开始 → 按业务风险分层 → 用 holdout 验证 → 看样本量 → 版本化 → 定期复盘。不要拿 holdout 反复调参。

### 4.5 LLM 边界

LLM 只做语义分析、疑似模块、修改建议和 few-shot 候选；硬门禁必须确定、可复现、可审计。

---

## 五、隔离：候选不能乱跑

- 路径：`/` 分隔的相对路径，拒绝盘符、冒号、控制字符、尾随空格/点、符号链接。
- 暂存：`.assay-staging` 完成复制/修改/哈希/manifest，再整体移动。
- 进程：超时终止整棵进程树；单流默认 1 MiB，超限终止。
- 容器：默认禁网、只读挂载、只读根文件系统、移除 capabilities、限制 CPU/内存/PID。
- Dashboard：只读、路径包含校验、符号链接拒绝、补丁 SHA-256。

---

## 六、Benchmark 方法

```bash
# 生成 10,000 条合成 case（示例脚本，可按需替换）
python scripts/generate_cases.py --count 10000 --output /tmp/assay-cases.jsonl

# 用 EchoAdapter 跑，适配器本身不访问网络
/usr/bin/time -v java -jar target/assay.jar run \
  --adapter io.assay.examples.EchoAdapter \
  --suite bench \
  --cases /tmp/assay-cases.jsonl \
  --store .assay/bench/store \
  --output .assay/bench/runs \
  --workers 20
```

记录：总耗时、cases/s、P50/P95、恢复耗时、存储写入耗时、峰值内存。
不要在没有固定环境的情况下比较不同机器；压测脚本和结果应进 `docs/benchmark.md`。

---

## 七、取舍

| 选择 | 收益 | 代价 |
|---|---|---|
| 零运行时依赖 | 单 jar、离线、可移植、可审计 | 自写 JSON/存储/HTTP，测试量大 |
| 文件存储 | 简单、可读、易恢复 | 无分布式锁/水平扩展 |
| LLM 不进硬门禁 | 可复现、抗注入 | 语义判断弱，需要人工 |
| 诊断只做辅助 | 不误伤非确定性轨迹 | 不是因果根因 |
| 只读 Dashboard | 安全、无构建 | 无在线编辑/协作 |

---

## 八、如果只记三句话

- **恢复**：批次 + manifest + 尾部修复 + 文件锁，保证崩溃后能继续且不混数据。
- **首错**：LCS 对齐 + 字段证据，把“失败了”变成“哪一步、哪个字段、从什么变成什么”。
- **隔离**：路径、进程、容器、输出四道边界，候选不能碰宿主。
