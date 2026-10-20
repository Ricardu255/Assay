# Assay OpenTelemetry Tracing

可选模块，把 `EvaluationListener` 生命周期事件转成 OpenTelemetry span。核心 `assay` 模块仍然零运行时依赖。

## 构建

```bash
mvn -q -pl :assay-tracing-otel -am test
# 或构建全部模块
mvn -q test
```

## 使用

```java
OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .build();
Tracer tracer = openTelemetry.getTracer("io.assay");

EvaluationListener tracing = new OtelEvaluationListener(tracer);
EvaluationEngine engine = new EvaluationEngine(
        adapter, store, null, 8, 0, 30, false, "agent-v1", tracing);
```

Spring Boot 里注册一个 `EvaluationListener` bean 即可，Starter 会自动注入：

```java
@Bean
EvaluationListener assayTracing(Tracer tracer) {
    return new OtelEvaluationListener(tracer);
}
```

## Span 结构

```text
assay.run
├── assay.case  (C1)
│   ├── assay.adapter
│   ├── assay.rules
│   └── assay.llm
├── assay.case  (C2)
│   ├── assay.adapter
│   ├── assay.rules
│   └── assay.llm
├── assay.store
└── assay.batch
```

- `assay.case` 的 parent 是同一个 run 的 `assay.run`。
- `assay.adapter` / `assay.rules` / `assay.llm` 的 parent 是当前 case。
- `assay.store` 的 parent 是 run；每次批量落盘一个 span。
- `assay.run` 在成功时 status=OK，失败时 status=ERROR，并记录异常。
- `assay.case` 按 hard pass/fail 设置 status。
- `assay.batch` 的 parent 是 run。

## 属性

| 属性 | 适用 span |
|---|---|
| `assay.run_id` | run / stage |
| `assay.case_id` | case / adapter / rules / llm |
| `assay.suite` / `assay.source` | run / case |
| `assay.status` | run / case / failed stage |
| `assay.hard_pass` / `assay.soft_warnings` | case |
| `assay.stage` | adapter / rules / llm / store |
| `collect_few_shot` | llm |
| `assay.case_count` | run |
| `attempt` | adapter |
| `checks` / `trace_count` | rules |
| `count` | store |
| `assay.batch.size` | batch |
| `assay.duration_ms` | run / stage / batch |
| `assay.error` | failed run / failed stage |

## 边界

- 不内置 exporter / collector；由使用方的 OTel SDK 决定。
- 已覆盖 adapter / rules / llm / store 的 stage span。
- 不在核心模块加 OTel 依赖；`app` 的 enforcer 会阻止反向依赖。
- 当前 batch span 没有 run 上下文；如果要求严格父子关系，后续把 run_id 加入 batch 回调。
