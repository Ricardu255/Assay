# Assay Spring Boot Starter

可选模块，把 Assay 的 `Results` 和 `EvaluationEngine` 接成 Spring Boot bean。核心 `assay` 模块仍然零运行时依赖；这个 starter 只做自动配置，不接管 Agent 调用。

## 构建

先在仓库根目录安装核心构件，再构建 starter：

```bash
mvn -q -DskipTests install
mvn -q -f extensions/assay-spring-boot-starter/pom.xml test
```

## 配置

```yaml
assay:
  store:
    type: file        # file | jdbc
    path: .assay/store
  workers: 8
  retries: 0
  timeout-seconds: 30
  collect-few-shot: false
  run-identity: my-agent-v1
```

- `file`：使用 `ResultStore`，`path` 是本地目录。
- `jdbc`：使用 `JdbcResultStore`，需要额外提供一个 `DataSource` bean；驱动不在 starter 内。
- `EvaluationEngine` 只在存在 `ProjectAdapter` bean 时创建，避免框架猜测如何调用 Agent。

## Java 配置示例

```java
@Configuration
class AssayAdapterConfiguration {

    @Bean
    ProjectAdapter supportAgentAdapter() {
        return new ProjectAdapter(
                "support-agent",
                (evalCase, context) -> callAgent(evalCase),
                (handle, evalCase) -> readTrace(handle),
                List.of(Rule.of("route", "fields.route", "route")),
                List.of());
    }
}
```

自动配置会注入这个 `ProjectAdapter`，并创建 `EvaluationEngine`。

## 边界

- 不做 schema migration：JDBC 表由 `JdbcResultStore` 首次打开时 `create table if not exists` 创建；生产环境应自己管理迁移。
- 不做分布式调度：`assay_lock` 表只提供 run 级互斥；进程崩溃后的锁行需要人工清理。
- 不接管模型/工具/沙箱：这些仍由领域适配器和 Assay 现有 Runner 负责。
