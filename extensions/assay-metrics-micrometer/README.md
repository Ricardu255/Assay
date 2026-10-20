# Assay Micrometer Metrics

可选模块，把 `EvaluationListener` 生命周期事件转成 Micrometer 指标。核心 `assay` 模块仍然零运行时依赖。

## 构建

```bash
mvn -q -DskipTests install
mvn -q -f extensions/assay-metrics-micrometer/pom.xml test
```

## 使用

```java
MeterRegistry registry = new SimpleMeterRegistry();
EvaluationListener metrics = new MicrometerEvaluationListener(registry);

EvaluationEngine engine = new EvaluationEngine(
        adapter,
        store,
        null,          // reviewer
        8,             // workers
        0,             // retries
        30,            // timeout seconds
        false,         // collect few-shot
        "agent-v1",   // run identity
        metrics);
```

Spring Boot 里只要把 `EvaluationListener` 注册成 bean，Starter 会自动注入：

```java
@Bean
EvaluationListener assayMetrics(MeterRegistry registry) {
    return new MicrometerEvaluationListener(registry);
}
```

## 指标

| 指标 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `assay.runs.started` | Counter | `suite`, `source` | 开始的 run 数 |
| `assay.runs` | Counter | `suite`, `source`, `status` | 结束的 run 数 |
| `assay.run.duration` | Timer | `suite`, `source`, `status` | run 总耗时 |
| `assay.cases` | Counter | `suite`, `status` | 已评测 case 数 |
| `assay.case.duration` | Timer | `suite`, `status` | 单 case 耗时 |
| `assay.batches` | Counter | — | 落盘批次数 |
| `assay.batch.size` | DistributionSummary | — | 每批 case 数 |
| `assay.store.append` | Timer | — | 单次批量落盘耗时 |

`status` 目前是 `passed` / `failed`；`suite` / `source` 会被截断到 64 字符。

## 基数控制

**不要把 run_id / case_id 当标签。** 这两个值是无界的，直接进标签会让时间序列爆炸。当前实现只用 run_id 作为本地 map key 计算耗时，不写进指标标签。

## 边界

- 只覆盖 run / case / batch，不覆盖 adapter、规则、LLM 的细粒度耗时；这些需要更细的 span。
- 不包含 OTel spans；OTel 是下一个可选模块。
- 没有内置 Prometheus endpoint；由使用方的 Micrometer registry/exporter 决定。
