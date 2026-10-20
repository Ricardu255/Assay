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
├── assay.case  (C2)
└── assay.batch
```

- `assay.case` 的 parent 是同一个 run 的 `assay.run`。
- `assay.run` 在成功时 status=OK，失败时 status=ERROR，并记录异常。
- `assay.case` 按 hard pass/fail 设置 status。
- `assay.batch` 记录批量落盘；当前是独立 span，不强制挂到 run 下。

## 属性

| 属性 | 适用 span |
|---|---|
| `assay.run_id` | run |
| `assay.case_id` | case |
| `assay.suite` / `assay.source` | run / case |
| `assay.status` | run / case |
| `assay.hard_pass` / `assay.soft_warnings` | case |
| `assay.case_count` | run |
| `assay.batch.size` | batch |
| `assay.duration_ms` | run / batch |
| `assay.error` | failed run |

## 边界

- 不内置 exporter / collector；由使用方的 OTel SDK 决定。
- 不覆盖 adapter / rules / LLM 的细粒度 span；这些需要后续扩展 listener 的 stage 事件。
- 不在核心模块加 OTel 依赖；`app` 的 enforcer 会阻止反向依赖。
- 当前 batch span 没有 run 上下文；如果要求严格父子关系，后续把 run_id 加入 batch 回调。
