# Assay Benchmark

> 目的：给“这个框架能不能跑量”一个可复现的基线，而不是只凭感觉。
> 这不是生产容量承诺；适配器、存储介质、Trace 大小、规则数量都会显著影响数字。

## 环境

| 项 | 值 |
|---|---|
| 日期 | 2026-10-20 |
| 操作系统 | Windows（`Windows_NT`） |
| CPU | AMD64 Family 25 Model 97 Stepping 2；32 个逻辑处理器 |
| JDK | 17.0.12 LTS |
| Maven | 3.9.4 |
| 存储 | 文件存储（`.assay/bench/store`） |
| 适配器 | `io.assay.examples.EchoAdapter`（无网络、无模型调用、单步 Trace） |
| 用例 | 1,000 条合成 JSONL |

## 命令

```bash
# 1. 生成 1,000 条用例（无 BOM 的 UTF-8）
python scripts/generate_cases.py --count 1000 --output .assay/bench/cases.jsonl

# 2. 运行
java -jar target/assay.jar run \
  --adapter io.assay.examples.EchoAdapter \
  --suite bench \
  --cases .assay/bench/cases.jsonl \
  --store .assay/bench/store \
  --output .assay/bench/runs \
  --workers 20
```

生成脚本的等价最小逻辑：

```python
import json
with open(".assay/bench/cases.jsonl", "w", encoding="utf-8", newline="\n") as handle:
    for index in range(1, 1001):
        handle.write(json.dumps({
            "id": f"B{index:04d}",
            "scenario": "bench",
            "input": {"message": f"hello {index}"},
            "expected": {"route": "ECHO", "keyword": "hello"},
        }, ensure_ascii=False) + "\n")
```

## 结果

| Workers | 用例数 | 总耗时 | 吞吐 |
|---:|---:|---:|---:|
| 8 | 1,000 | 912 ms | ~1,096 cases/s |
| 20 | 1,000 | 908 ms | ~1,101 cases/s |

观察：

- 这个 adapter 几乎是空操作，所以数字主要反映**框架调度、规则检查、Trace 归一化、批次落盘和报告生成**的开销。
- 8 和 20 workers 接近，说明在这个极轻负载下，瓶颈已经不在 CPU 并行度，而在单次运行的固定开销和文件写入。
- 真实 Agent 的延迟通常远大于框架开销，实际吞吐会由 Agent/网络/模型决定。

## 不要过度解读

- 这不是“生产级 1,100 QPS”的承诺；没有真实模型、没有大 Trace、没有复杂规则、没有 JDBC。
- 没有测 P95/P99；当前只有总耗时，因为样例数据过于均匀。
- 没有做 JVM warmup 隔离；两次运行都包含 JVM 启动。
- 没有比较 file vs JDBC；JDBC 的批量 upsert 需要单独测。

## 下一步

- 用真实 adapter（HTTP/AgentScope/ADK）跑 100 条，记录 P50/P95。
- 增加 10,000 条 case、复杂 Trace、20 条规则的组合。
- 对比文件存储与 JDBC 存储的吞吐/延迟。
- 把这些数字写进 README 的“工程证据”，而不是只放“202 tests”。
