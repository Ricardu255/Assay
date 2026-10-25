# Assay Benchmark

> 目的：给“这个框架能不能跑量”一个可复现的基线，而不是只凭感觉。
> 这不是生产容量承诺；适配器、存储介质、Trace 大小、规则数量都会显著影响数字。

## 环境

| 项 | 值 |
|---|---|
| 日期 | 2026-10-22 |
| 操作系统 | Windows（`Windows_NT`） |
| CPU | AMD64 Family 25 Model 97 Stepping 2；32 个逻辑处理器 |
| JDK | 17.0.12 LTS |
| Maven | 3.9.4 |
| 文件存储 | `ResultStore`，本地磁盘 |
| JDBC 存储 | H2 2.2.224，嵌入式文件库 |
| 用例 | 10,000 条合成 JSONL |
| Workers | 20 |

## 两种适配器

| 名称 | 说明 |
|---|---|
| `EchoAdapter` | 单步、无网络、无模型调用；主要测框架固定开销 |
| `BenchmarkAgentAdapter` | 4 个 Trace 事件、5 条硬规则 + 1 条软规则；模拟多步 Agent，但仍是本地确定性实现 |

`BenchmarkAgentAdapter` 不是“真实 LLM Agent”：
它刻意去掉模型和网络方差，用来观察**调度、规则、Trace 归一化、存储和报告**的成本。
真实 HTTP/LLM Agent 的延迟通常会远大于框架开销，不能把下表的 cases/s 当成真实 Agent 容量。

## 命令

```bash
# 构建、生成 10,000 条用例、跑 file/JDBC × echo/agent 四种组合
python scripts/benchmark.py --count 10000 --workers 20
```

脚本会：

1. `mvn -DskipTests -pl :assay -am clean package`
2. 调用 `scripts/generate_cases.py` 生成 `target/bench/*.jsonl`
3. 对文件存储和 H2 JDBC 分别运行 CLI
4. 把结果写到 `target/bench/results.json`

只跑一部分：

```bash
python scripts/benchmark.py --skip-build --count 10000 --scenarios agent --stores file
```

JDBC 需要 H2 驱动；脚本会从本机 `~/.m2` 找 H2 jar，不把它打进 `assay.jar`。

## 结果

2026-10-22，单次运行，包含 JVM 启动：

| Scenario | Store | Cases | Wall ms | Cases/s | Hard failures | Events/case |
|---|---:|---:|---:|---:|---:|---:|
| echo | file | 10,000 | 2,683 | ~3,727 | 0 | 1 |
| echo | jdbc | 10,000 | 4,020 | ~2,488 | 0 | 1 |
| agent | file | 10,000 | 4,011 | ~2,493 | 0 | 4 |
| agent | jdbc | 10,000 | 5,703 | ~1,754 | 0 | 4 |

观察：

- 10k 比之前 1k 的 ~1,100 cases/s 更高，主要是固定启动成本被摊薄，不是实现突然变快。
- 文件存储比嵌入式 H2 快约 1.4～1.5 倍；H2 结果包含事务、批量写入和 JDBC 连接开销。
- 多步参考 Agent 只有 4 个事件，规则也只有 5 条硬规则；真实 Agent 往往有更多事件、工具参数和更长输出。
- 这四个数字只说明“框架本身能跑 10k 级合成负载”，不说明真实模型吞吐。

## 关于真实 Agent

当前仓库没有稳定的外部 LLM/Agent endpoint，也没有把 API key 写进基准脚本，因此没有伪造“真实 Agent”数字。

接真实 Agent 时，推荐用已有的 `HttpProjectAdapter`：

```bash
java -jar app/target/assay.jar run \
  --adapter path/to/http-agent.json \
  --suite real-agent-bench \
  --cases path/to/real-cases.jsonl \
  --store jdbc:postgresql://... \
  --output target/real-agent-runs \
  --workers 4
```

这时瓶颈通常已经是模型/网络，应该记录：

- 每个 Agent 调用的 P50/P95
- 端到端 cases/min
- 失败率、超时率、重试率
- Token 成本（如果 Agent 返回）

## 不要过度解读

- 没有 JVM warmup 隔离；四个数字都包含 JVM 启动。
- H2 是嵌入式文件库，不是 PostgreSQL/MySQL 服务端；JDBC 数字不能代表生产数据库。
- `JdbcResultStore` 会为 H2 file URL 自动补 `DB_CLOSE_DELAY=-1`，避免“关闭最后一个连接再重新打开”后变成 read-only；基准脚本也显式带上该参数，减少环境差异。
- 没有测真实 Agent 的 P50/P95；`trace_duration` 是适配器声明的步骤耗时，不是墙钟延迟。
- 没有做多轮重复取中位数；单次结果只适合做趋势参考。

## 下一步

- 用真实 HTTP/AgentScope/ADK 跑 100～1,000 条，记录 P50/P95 和超时。
- 用 PostgreSQL 跑 JDBC 基准，和 H2 分开报告。
- 增加 10k 条、20 条规则、10 个 Trace 事件的组合。
- 加入 JVM warmup 与多次重复，报告均值和方差。
