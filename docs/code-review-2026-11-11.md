# Assay 代码规范性与整洁性 Review（复核版）

- **复核日期**：2026-11-11
- **范围**：`D:/code/Java/Assay` 全仓，git 跟踪的 267 个文件（193 个 Java）
- **方法**：把上一轮口头 review 的 10 条结论逐条回到源码、配置、构建产物核对，标记「属实 / 部分属实（需修正措辞）/ 撤回」；并补查复核过程中新暴露的问题
- **变更范围**：复核分析本身为只读；随后按第 6 节清单完成了一轮整改（见第 9 节），源码、README、Dockerfile 与工作区记忆日志已更新

---

## 1. 结论摘要

工程基线是健康的：多模块结构清晰、CI 完整、静态检查与测试全绿、没有阻断性 bug。上一轮指出的**核心判断成立**——主要债务集中在「故障静默、弱类型数据契约、跨文件复制粘贴、门禁偏松」四类。

但重新读代码后，上一轮有 **3 处表述需要修正、1 条结论必须撤回**（另 1 条降级为部署提示）：

| 修正 | 内容 |
|---|---|
| 撤回 | `SafePath.require(field, null)` 放行不是缺陷，而是**必需契约**（非 `move` 操作的 `destination` 必须为 `null`），且实际调用点都不会传入 null |
| 数字修正 | `AssayCli` 是 **18 个命令 / 53 个静态方法**，不是「20+ 命令」；「44 个 helper / 54 处 unchecked」是含测试的口径，主代码分别是 **35 / 41** |
| 定性修正 | `EvaluationSettings` 是**包私有 record**，外部根本用不了它，5 个 public 构造器是唯一入口；问题不是「有 settings 还留重载」，而是缺少对外的 Builder/工厂 |
| 定性修正 | JDBC 的 `portable` 只是 `JdbcSchema` 的类注释，README **只举了 H2 为例**、从未承诺任意数据库；`limit` 拼接是风格问题、**无注入风险**；Dockerfile 的模块清单**当前与根 pom 完全一致**，不是「不同步」 |

复核另外确认了 **5 个上一轮漏掉的细节**（N1–N5，见第 5 节；N4/N5 为复核补遗），并排除了 1 个一度怀疑的疑点。其中 `JobStore.list()` 三处限额不一致 + 线性扫描 payload 是**真实的行为边界缺陷**，优先级应排在 P1 末尾。

---

## 2. 复核基线（实测数据）

| 项目 | 结果 | 来源 |
|---|---|---|
| 模块 / 语言 | 13 个 Maven 模块，Java 17 | 根 `pom.xml` |
| 代码规模 | 193 个 Java（133 主 / 60 测试），主代码 21,510 行，14 个文件超 500 行 | `git ls-files` + `wc -l` |
| 构建门禁 | `mvn -B -o -q -DskipTests verify` 退出码 0 | 本次离线重跑 |
| Checkstyle | 0 error（13 个模块 + 根聚合，共 14 个报告文件） | `**/target/checkstyle-result.xml` |
| SpotBugs | 0 bug | `**/target/spotbugsXml.xml` |
| 测试 | 346 个，0 failure / 0 error / 1 skipped | `**/target/surefire-reports/*.txt` |
| 覆盖率 | 聚合行覆盖率 82.1%，主要模块 71.3%–90.7%；`assay-examples` 仅 17.9%，不在该区间内 | `app/target/site/jacoco-aggregate/jacoco.csv`（完整测试快照） |
| 仓库整洁 | 无 `.class` / `.jar` / `target` 被跟踪；`.gitignore` 覆盖 `target/`、`.assay/`、`dist/` | `git ls-files` |

> 注：本次为验证门禁重跑过 `-DskipTests verify`，会刷新部分**单模块** JaCoCo 报告；上表覆盖率只引用聚合快照，单模块数值不作为结论依据。

---

## 3. 复核结论总表

| # | 上一轮结论 | 复核判定 | 关键依据 |
|---|---|---|---|
| F1 | 零日志框架，服务端降级与队列异常静默 | ✅ 属实 | 全仓 0 处 SLF4J/Logger；`RunService.java:129-133,145-147,161-165,192-194,223-225`；`JobRunQueue.java:159-166` |
| F2 | JDBC 方言与 `portable` 宣称不符、拼接 `limit` | ⚠️ 部分属实 | `JdbcSchema.java:7,24`、`JdbcJobStore.java:181,336` 属实；但 README:541 只举 H2，未承诺跨库；拼接无注入风险 |
| F3 | 鉴权默认全开，非本机部署有风险 | ⚠️ 属实但为有意设计 | `AssayServerProperties.java:21`、`SecurityConfig.java:41`、README:165 明确说明；`ServerSecurityTest` 覆盖了配置 token 后的行为 |
| F4 | `Map<String,Object>` 弱类型契约 + helper 遍地复制 | ✅ 属实（口径修正） | 主代码 41 处 `@SuppressWarnings("unchecked")`、35 个私有 `asMap/asList/intValue`；`Values.toInt` 包私有 |
| F5 | 两套存储业务规则逐行重复 | ✅ 属实 | `ResultStore.java:335-339` vs `JdbcCaseRepository.java:141-145` |
| F6 | 上帝类 + 构造器膨胀 | ✅ 属实（数字/定性修正） | `AssayCli.java` 1535 行 / 18 命令 / 53 静态方法；`EvaluationEngine.java:57,61,73,96,120` 共 5 个 public 构造器，最长 13 参数 |
| F7 | 质量门禁偏松 | ✅ 属实 | `config/checkstyle/checkstyle.xml` 仅 6 个检查模块；`config/spotbugs/exclude.xml` 全局排除 2 类 |
| F8 | 10 个测试归属错模块 | ✅ 属实（但事出有因） | 见下方清单，均为需要 file-store fixture 的集成测试 |
| F9 | Dockerfile 模块清单与 pom 不同步 | ❌ 撤回 | 13 个 COPY 的 pom 与根 pom 的 13 个 module **逐项完全一致**（`diff` 为空）；仅保留「手工清单需维护」的提示 |
| F10 | `SafePath.require(null)` 是缺陷 / `.assay` 副本 / 面试笔记 | ❌ 撤回 1 项 + 降级 2 项 | null 放行是必需契约，缺陷不成立；后两项属工作区/文档组织，不是代码缺陷 |

---

## 4. 逐条复核详情

### F1 零日志 + 静默失败 ✅ 属实（最高优先级）

主代码 133 个类**没有任何日志框架**，45 处 `System.out/err` 全部集中在 CLI（命令行输出是合理的）。问题在服务端：降级路径把故障伪装成「没有数据」。

```java
// assay-server/src/main/java/io/assay/server/run/RunService.java:124-133
try (Results store = openStore(storePath)) {
    for (Map<String, Object> run : store.listRuns()) { ... }
} catch (UnsupportedOperationException ignored) {
    // Backends without run listing still expose live queue jobs below.
} catch (RuntimeException ignored) {
    // Listing must stay available even when the store is temporarily unavailable.
}
```

`list()` 在 store 不可用时返回空/残缺列表；`get()`（`:159-165`）回退到 queue 记录；`cases()`（`:190-194`）直接返回 `List.of()`。调用方无法区分「无运行记录」与「存储故障」。

```java
// assay-runtime/src/main/java/io/assay/execution/JobRunQueue.java:150-167
while (!closed.get()) {
    try {
        if (!runOnce()) { Thread.sleep(pollMillis); }
    } catch (InterruptedException interrupted) { ... }
      catch (RuntimeException failure) {          // 无日志、无计数、无退避上限
        try { Thread.sleep(pollMillis); } catch (...) { ... }
    }
}
```

**边界说明（比上一轮更精确）**：`execute()`（`:194-199`）内部已经把评测运行时异常转成 `jobs.fail()`，所以 `pollLoop` 吞掉的主要是 `jobs.claim()` / `jobs.list()` 等**存储层异常**，以及 `jobs.fail()` 自身再次抛出的异常——也就是「整个队列停摆」的那类故障，恰恰最需要告警，却完全不可观测。

`RunNotifier` 的 10 处 `catch (RuntimeException ignored)` 是**有意设计**（类注释：`Observers never change evaluation truth`），不应改抛异常，但可以补 debug 级日志，否则 Micrometer/OTel 扩展点坏了无人知晓。

### F2 JDBC 方言与 SQL 拼接 ⚠️ 部分属实

```java
// assay-store-jdbc/src/main/java/io/assay/store/jdbc/JdbcJobStore.java:332-336
int max = Math.max(1, Math.min(500, limit));   // max 是 int，已 clamp 到 1..500
PreparedStatement select = connection.prepareStatement(
        "select * from assay_job order by created_at desc limit " + max);
```

- **拼接不是安全漏洞**：`max` 由 int 运算得出，无外部字符串进入 SQL；问题只是破坏该文件其余部分全参数化的一致性，应改成 `limit ?` + `setInt`。
- **方言用法属实**：`JdbcJobStore.java:181` 的 `limit 1 for update`、`JdbcSchema.java:24` 的 `text`、`boolean`、`timestamp` 都是 H2/MySQL/PostgreSQL 系语法；`README.md:541` 只以 H2 举例，还专门做了 H2 URL 的 `DB_CLOSE_DELAY=-1` 处理。
- **但「宣称 portable」是代码注释层面的落差**，不是 README 的承诺：`JdbcSchema.java:7` 写的是 `Creates the portable JDBC schema...`。准确的风险描述是：注释 + 「`--store` 传 `jdbc:` URL 即可切换」的措辞容易让用户以为换库即用，实际在 Oracle/MSSQL 上会失败。

建议：把注释改成「H2/MySQL/PostgreSQL 兼容」，在 README 明确支持的数据库清单，或引入方言抽象（至少把 `limit` / `for update` 集中到一处）。

### F3 鉴权默认全开 ⚠️ 属实，但属文档化的有意设计

```java
// assay-server/src/main/java/io/assay/server/config/AssayServerProperties.java:21
private List<String> authTokens = new ArrayList<>();      // 默认空

// assay-server/src/main/java/io/assay/server/security/SecurityConfig.java:38-42
if (tokensConfigured) { auth.anyRequest().authenticated(); }
else                  { auth.anyRequest().permitAll(); }
```

- README:165 明确写了「列表为空时保持本地开放模式」，`ServerSecurityTest` 也只在显式设置 `auth-tokens=test-token` 时验证 401/200；`ApiTokenFilter` 用 `MessageDigest.isEqual` 做常量时间比较、且遍历 token 不短路，实现本身是对的。
- 真正的风险在部署侧：`AssayServerApplication` 未限制绑定地址（Spring Boot 默认 `0.0.0.0`），直接运行 `assay-server` 时也不注入 token；一旦暴露到非本机网络，`POST /api/runs` 可直接触发评测任务。注意根目录 Dockerfile 构建的是 CLI 发行包（`-pl :assay -am`，`app` 不依赖 `assay-server`），镜像里没有 HTTP 服务，**该风险与 Dockerfile 无关**。
- 对比：`DashboardServer` 用 `InetAddress` 绑定 IPv4 loopback（类注释 `:26`，绑定代码 `:55`），不受此问题影响。

建议保留本地开发便利性，但增加「非 loopback 绑定且 token 为空时启动 fail-fast 或 WARN」。

### F4 弱类型契约与 helper 复制 ✅ 属实（口径修正）

- **120/193** 个 Java 文件出现 `Map<String, Object>`。
- `@SuppressWarnings("unchecked")`：**主代码 41 处 + 测试 13 处 = 54 处**（上一轮的 54 是全仓口径）。
- 私有 `asMap` / `asList` / `intValue`：**主代码 35 个 + 测试 9 个 = 44 个**（上一轮的 44 是全仓口径），分布在 core 11 个文件、runtime 6 个、store-file 4 个、cli 3 个、dashboard 1 个。

```java
// assay-core/src/main/java/io/assay/model/Values.java:7,26   —— 已有实现，但包私有
final class Values {
    static int toInt(Object value, int fallback) { ... }

// assay-core/src/main/java/io/assay/engine/EvaluationEngine.java:755  —— 逐行复制，改名
    private static int intValue(Object value, int fallback) { ... }

// assay-core/src/main/java/io/assay/report/Reporting.java:694        —— 同名但语义不同
    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;   // 不解析 String
    }
```

`Values` 位于 `io.assay.model`、`EvaluationEngine` 位于 `io.assay.engine`，可见性不足直接导致复制；而 `Reporting.intValue` 与 `EvaluationEngine.intValue` **同名不同义**，比纯重复更容易埋 bug。

弱类型的具体后果（复核新增证据）：`RunJobs.status()`（`assay-runtime/.../RunJobs.java:56-67`）用 switch 把 `JobStatus` 映射成 `"queued"/"running"/"passed"/"failed"/"cancelled"`，而 `RunService.finalizing()`（`:182-184`）又把 `"passed"/"failed"/"budget_exhausted"/"cancelled"` 硬编码一遍；同一套状态在两处各自维护。字符串键 `"status"` 在主代码出现 89 次、`"run_id"` 71 次，没有常量表，拼错不会编译报错。

### F5 两套存储重复业务规则 ✅ 属实

```java
// assay-store-file/src/main/java/io/assay/store/file/ResultStore.java:335-339
if ("hard".equals(check.level()) && !check.passed()) {
    record.put("hard_pass", false);
} else if (("soft".equals(check.level()) || "candidate".equals(check.level())) && !check.passed()) {
    record.put("soft_warning_count", intValue(record.get("soft_warning_count")) + 1);
}

// assay-store-jdbc/src/main/java/io/assay/store/jdbc/JdbcCaseRepository.java:141-145
if ("hard".equals(check.level()) && !check.passed()) {
    record.put("hard_pass", false);
} else if (("soft".equals(check.level()) || "candidate".equals(check.level())) && !check.passed()) {
    record.put("soft_warning_count", JdbcSupport.intValue(record.get("soft_warning_count")) + 1);
}
```

除工具类名不同外逐字相同。这正是「改一处忘一处」会让两种后端给出不同评测结论的地方，建议抽到 core 的共享方法并补一组跨后端参数化测试。

### F6 上帝类与构造器膨胀 ✅ 属实（数字/定性修正）

- `AssayCli.java` 1535 行、**18 个** `CommandLine.Spec`（上一轮写「20+」不准确）、**53 个**静态方法，同时承担命令注册、参数声明/分发、交互式 quickstart 向导、报告渲染、退出码判定（真正的参数解析在 `CommandLine.java`，234 行）。其中 `auditScope()`（`:904-1063`）约 160 行，是文件内最大的单方法。表驱动分发（`COMMANDS`）是好设计，但文件本身仍过载。
- `EvaluationEngine.java` 有 5 个 public 构造器（`:57,61,73,96,120`）+ 1 个 private（`:147`），最长 13 参数：

```java
// assay-core/src/main/java/io/assay/engine/EvaluationEngine.java:120-146
public EvaluationEngine(ProjectAdapter adapter, Results store, LlmReviewer reviewer,
        int workers, int retries, double timeoutSeconds, boolean collectFewShot,
        String runIdentity, EvaluationListener listener, EvaluationBudget budget,
        RetryPolicy retryPolicy, CircuitBreaker adapterBreaker, CircuitBreaker reviewerBreaker) {
    this(adapter, store, reviewer, new EvaluationSettings(...));
}
```

**定性修正**：`EvaluationSettings`（独立文件 `EvaluationSettings.java:12`）是**包私有 record**（`record EvaluationSettings(...)`，无 `public`），外部模块无法构造它，因此这 5 个重载不是「明明有 settings 还留着的历史包袱」，而是当前唯一的对外入口。真正该做的是把 settings 提升为 public 或提供 `builder()`，再逐步废弃长参数构造器，而不是简单删掉重载。

### F7 门禁偏松 ✅ 属实

`config/checkstyle/checkstyle.xml` 实际只有 6 个检查模块：`FileTabCharacter`、`LineLength(max=200)`、`NewlineAtEndOfFile`，以及 `TreeWalker` 下的 `RedundantImport`、`UnusedImports`、`AvoidStarImport`。没有命名、可见性、import 顺序、复杂度、Javadoc 规则。

因此以下都能通过门禁：

```java
// assay-core/src/main/java/io/assay/engine/EvaluationEngine.java:22-28  —— import 顺序错乱
import java.util.concurrent.Future;
import java.security.MessageDigest;      // 应在 java.util 之前
import java.nio.charset.StandardCharsets;

// assay-runtime/src/main/java/io/assay/execution/JobRunQueue.java:7 已 import java.nio.file.Path
// :209-212 却又用全限定名
java.nio.file.Path dir = execution.reportDir();
java.nio.file.Files.createDirectories(dir);
dir.resolve("run-summary.json"), io.assay.json.Json.write(...)
```

`DashboardServer.java:277` 同样在已 `import java.util.LinkedHashMap`（`:16`）的情况下写 `new java.util.LinkedHashMap<>()`，说明这是系统性风格漂移，不是个别遗漏。

`config/spotbugs/exclude.xml` 全局排除 `EI_EXPOSE_REP` / `EI_EXPOSE_REP2`（注释写明了 record 不可变约定，理由充分），另有一条针对 `ValueOps` 的 `NP_BOOLEAN_RETURN_NULL`。这部分是有意取舍，但 EI 两类是全局豁免，值得定期复核。

### F8 测试归属错位 ✅ 属实（但事出有因）

10 个测试放在别的模块：

```
assay-cli/src/test/java/io/assay/http/HttpProjectAdapterTest.java
assay-cli/src/test/java/io/assay/trace/TraceFileAdapterTest.java
assay-store-file/src/test/java/io/assay/engine/EvaluationBudgetTest.java
assay-store-file/src/test/java/io/assay/engine/EvaluationListenerTest.java
assay-store-file/src/test/java/io/assay/engine/EvaluationRetryAndBreakerTest.java
assay-store-file/src/test/java/io/assay/engine/FrameworkTest.java
assay-store-file/src/test/java/io/assay/engine/RunIntegrityTest.java
assay-store-file/src/test/java/io/assay/evolve/EvolutionTest.java
assay-runtime/src/test/java/io/assay/concurrency/ConcurrencySafetyTest.java
assay-runtime/src/test/java/io/assay/model/EvolutionConfigurationTest.java
```

它们测的是 `engine` / `evolve` / `http` / `trace` / `concurrency`（以及 core model），却依赖 `ResultStore` 等 file-store fixture（如 `FrameworkTest` 直接 import `io.assay.store.file.ResultStore`），放在 store-file/cli/runtime 模块是为了拿到完整 classpath，**属于集成测试而非放错位置**。代价是：单独执行被测模块（如 `mvn test -pl assay-core`）拿不到这些覆盖，CI 也只校验聚合覆盖率。建议新建 `assay-integration-tests` 模块，或在 README「测试」章节说明这种归属约定。

### F9 Dockerfile 模块清单 ❌ 撤回「不同步」的说法

把根 `pom.xml` 的 `<module>` 与 Dockerfile 里 `COPY .../pom.xml` 排序后 `diff`，**13 项完全一致、无任何差异**。当前做法是：把 app 依赖链上的 7 个模块连源码一起 COPY，其余 6 个只 COPY pom 占位，再用 `mvn -q -DskipTests -pl :assay -am package` 只构建依赖链——设计是自洽的。

保留的风险只有两点（降级为提示）：手工清单在**新增模块**时需同步维护；`extensions/*`、`assay-server`、`assay-agentscope` 只放 pom 不放 src，将来若 app 依赖它们，会编译出空 jar 而不易察觉。CI 的 `docker build` 只构建不运行镜像，这类问题不会被发现。

### F10 撤回 `SafePath.require(null)` 缺陷 ❌

```java
// assay-core/src/main/java/io/assay/model/SafePath.java:30-33
public static void require(String field, String value) {
    if (value == null) { return; }        // 上一轮认为这是「校验被推迟」
    ...
}

// assay-core/src/main/java/io/assay/model/TextFileOperation.java:23-28,32-33
if ("move".equals(operation) && (destination == null || destination.isEmpty())) { throw ...; }
if (!"move".equals(operation) && destination != null) { throw ...; }   // 非 move 必须 null
SafePath.require("path", path);
SafePath.require("destination", destination);                            // 合法地传 null
```

`null` 放行是**必需契约**：`write`/`delete` 操作的 `destination` 按规定必须为 null，若 `require` 拒绝 null，正常的非 move 操作全部无法构造。其余调用点也都已保证非 null：`TextFileOperations.safeRelativeFile()`（`:28-31`）先显式判 null；`ArtifactCatalog.reportPath()`（`:243-250`）的 `relative` 来自 Base64 解码结果，非 null；`TextArtifactWorkspace`（`:122-125`）的 `name` 经过 `Names.safeName`。

唯一可提的是「可选字段语义未写进 `require` 的 Javadoc」，属于文档改进，不是缺陷。

另两项同理降级为工作区/文档组织提示：`.assay/build/assay-build` 是约 2.7 MB（磁盘占用约 3.4 MB）/ 115 个 Java 的本地源码副本（整个 `.assay` 才是 13 MB；该副本已被 gitignore，仓库内无脚本引用它），建议在 IDE 中排除以免误改重复类；`docs/assay-interview-prep.md` 1893 行属个人笔记，与代码无关。

---

## 5. 复核中新发现的细节与排除的疑点

### N1 `JobStore.list(limit)` 三处限额不一致 + 全量线性扫描（P1）

| 位置 | 数值 |
|---|---|
| `JobRunQueue.java:35,113` `LIST_LIMIT` | 1000 |
| `RunService.java:45,135` `LIST_LIMIT` | 200 |
| `FileJobStore.java:184` / `JdbcJobStore.java:333` | 都 `Math.min(500, limit)` 硬上限 |

```java
// assay-runtime/src/main/java/io/assay/execution/JobRunQueue.java:108-120
public Optional<Job> findJobByRunId(String runId) {
    for (Job job : jobs.list(LIST_LIMIT)) {              // 请求 1000，实际最多 500
        Object value = job.payload() == null ? null : job.payload().get("run_id");
        if (value != null && wanted.equals(String.valueOf(value))) { return Optional.of(job); }
    }
    return Optional.empty();
}
```

后果：队列积压超过 500 条时，**仍在排队、尚未落盘的旧 run 查不到对应 job**，`RunService.get()` 会返回 404（已完成的 run 因有持久化记录不受影响）。此外 `FileJobStore.list()`（`:183-192`）是「读完全部 job 文件 → 排序 → `subList`」，没有下推，随积压线性变慢；`findJobByRunId` 也是逐条解析 payload 找 `run_id`，没有按 run_id 索引。建议统一限额常量、给 `JobStore` 增加 `findByRunId`，或至少让上限来自同一配置。

### N2 类注释与实现漂移（条件性）

```java
// assay-dashboard/src/main/java/io/assay/web/DashboardServer.java:26
/** Serves a browser dashboard on IPv4 loopback, with GET-only report APIs. */
```

默认模式（`jobs == null`）下确实是只读的，但 `serve --worker` 启用队列后，`:88-90` 会把 POST 交给 `handlePost()`，`:137-183` 实现了 `POST /api/jobs` 的提交与取消，`:100-101` 在未启用时明确返回 503。注释没有说明这个条件分支，容易让维护者误以为整个服务永不写入。建议改成「read-only by default; POST job APIs require `--worker`」。

### N3 全限定名混用范围比上一轮更广

除 `JobRunQueue:209-212`、`TextFileOperations:110-153` 外，`DashboardServer:277,286-287` 在已 `import java.util.LinkedHashMap`（`:16`）的情况下仍写 `new java.util.LinkedHashMap<>()`，并混用未 import 的 `java.net.URLDecoder`。主代码中非 import 的 `java.*` 全限定名共 **76 处**（`TextFileOperations` 22、`AssayCli`/`CompareReport` 各 7、`DashboardServer` 4），范围确实比上一轮描述更广。建议加 `CustomImportOrder` 规则一次性收敛。

### N4 `JobRunQueue` 心跳异常会终止健康任务（P2）

```java
// assay-runtime/src/main/java/io/assay/execution/JobRunQueue.java:176-186
ScheduledFuture<?> beat = heartbeat.scheduleAtFixedRate(() -> {
    try {
        if (!jobs.heartbeat(jobId, workerId, lease) || jobs.isCancelRequested(jobId)) {
            cancelled.set(true);
            worker.interrupt();
        }
    } catch (RuntimeException failure) {
        cancelled.set(true);
        worker.interrupt();
    }
}, beatMillis, beatMillis, TimeUnit.MILLISECONDS);
```

心跳在这里指 worker 向共享 `JobStore` 续租，不是网络探测。`claim()` 把任务置为 `RUNNING` 并写入 `lease_expires_at`，之后每 `lease/3` 调一次 `heartbeat()` 续租；心跳停止超过 `lease` 后，其他实例的 `claim()` 会把过期任务重新入队（`FileJobStore.java:94-100`、JDBC 的 `reapExpired`）。因此「心跳失败」在代码里包含三种不同语义，但当前被合并处理：

| 情况 | 来源 | 含义 | 当前处理 |
|---|---|---|---|
| `heartbeat()` 返回 `false` | `FileJobStore:130-131`（job 不存在 / 非 RUNNING / leaseOwner 不是自己）；JDBC `update ... where lease_owner = ?` 影响 0 行 | **租约已丢失**（被其他 worker 认领、任务已结束或被删） | `cancelled=true` + 中断 → `CANCELLED`（合理） |
| `heartbeat()` 抛 `RuntimeException` | JDBC `SQLException` → `IllegalStateException("cannot renew job lease")`；文件存储 IO 异常 | **存储暂时不可达**，无法判断租约状态 | 同样 `cancelled=true` + 中断 → 可能 `CANCELLED`（**缺陷**） |
| `isCancelRequested()` 返回 `true` | 用户/API 取消 | 明确要求取消 | `cancelled=true` + 中断 → `CANCELLED`（合理） |

风险窗口是「心跳瞬时失败 → 存储在 `execute()` 处理前恢复 → `jobs.finish(..., CANCELLED, ...)` 成功」：一次短暂的 DB/文件锁抖动会把健康任务永久标记为终态 `CANCELLED`，既不按 lease 过期重试，也不会被 `claim()` 回收（若 `finish` 时存储仍不可用，异常会逃到 `pollLoop` 被吞掉，任务反而留在 `RUNNING` 等租约过期，这是符合预期的路径）。

建议：把 `false`（租约丢失）与异常（存储故障）分开处理——异常时记 WARN、等下一次心跳重试或让其自然过期后被回收，不要立即中断；若必须终止，也应标记可重试的 `FAILED` 而非终态 `CANCELLED`。同时给 `JobStore.heartbeat` 的接口补 Javadoc，明确 `false` 与异常各自的契约（当前 `JobStore.java:50` 没有说明）。

### N5 简洁性补充：CLI 选项与 Store 工厂重复

- CLI 选项声明：`store` 出现 11 次、`output` 6 次、`retry-*` 6 次；`serve` 与 `worker` 各自重复声明 `worker-id` / `lease-seconds` / `poll` / `retry-*`，可抽 `queueOptions(spec)` 之类的 helper。
- Store 工厂：CLI 的 `Stores.open()` 与 `RunService.openJobs()/openStore()`（`:250,257`）各自实现同一段 `jdbc:` 分支；`io.assay.support` 位于 core 且 server 依赖 core，可下沉为共享工厂。
- Job 视图：`RunService.jobRow()` 与 `JobRunQueue.submit()` 各自拼装 `run_id` / `job_id` / `status`。
- 单方法长度：`AssayCli.auditScope()`（`:904-1063`）约 160 行，是 CLI 内最长的单方法，建议按「选择 / 渲染 / 退出码」拆分。

### 已排除的疑点：Dashboard 非法查询参数的错误码

复核时一度怀疑 `/api/rule-history?limit=abc` 中的 `Integer.parseInt` 会落到 `catch (IOException | RuntimeException)` 而返回 500。读全 `DashboardServer.java:97-133` 的 catch 顺序后确认：`:129` 的 `catch (IllegalArgumentException)` 排在 `:131` 之前，而 `NumberFormatException` 正是其子类，会正确返回 400；`query()` 中 `URLDecoder.decode` 抛出的 `IllegalArgumentException` 同理。**该疑点不成立，不构成问题**，在此留痕避免后续重复排查。

---

## 6. 修订后的问题清单（按优先级）

**P1 — 影响线上排障/正确性**

1. 全仓零日志框架；`RunService` 5 处降级、`JobRunQueue.pollLoop:159` 吞异常，故障不可观测（F1）
2. `JobStore.list` 限额 1000/200/500 不一致 + 全量扫描，队列积压时排队中的旧 run 查不到（N1，**新**）
3. 非本机部署时 token 为空即全放行，且无绑定地址约束（F3，有意设计但需部署侧防护）

**P2 — 结构性整洁债**

4. 弱类型契约：120 文件用 `Map<String,Object>`、主代码 41 处 unchecked、35 个复制的 helper、`Values` 可见性不足（F4）
5. 两套存储的 `hard/soft/candidate` 规则逐行重复（F5）
6. JDBC 方言注释/文档落差 + `limit` 拼接风格（F2）
7. `AssayCli` 1535 行 / 18 命令；`EvaluationEngine` 5 个 public 长参数构造器，缺 public Builder/settings（F6）
8. Checkstyle 仅 6 个检查模块、无命名/顺序规则；import 乱序与全限定名混用（F7、N3）；CLI 选项声明与 Store 工厂重复（N5）
9. 10 个集成测试归属与被测模块不一致（F8）
10. `JobRunQueue` 心跳异常会把健康任务标记为 `CANCELLED`（N4）

**P3 — 提示级**

11. `DashboardServer` 类注释未说明 `--worker` 下支持 POST（N2）
12. Dockerfile 手工模块清单当前正确，但新增模块需同步（F9 修正版）
13. `.assay/build` 本地源码副本建议在 IDE 排除；面试笔记文档建议移出项目 docs（F10 修正版）

---

## 7. 建议的整改顺序

1. 先做**可观测性**：引入 SLF4J（server 已有 Spring Boot 自带 Logback），给降级、队列异常、监听器异常加 WARN/DEBUG；统一 `LIST_LIMIT`，给 `JobStore` 补按 run_id 查询。
2. 同时修**队列语义**：`heartbeat()` 返回 `false`（租约丢失）才停止任务；抛异常（存储故障）记 WARN 后继续，或让租约自然过期由其他实例回收，禁止把瞬时异常标记为 `CANCELLED`（N4）。
3. 再做**契约收敛**：抽 `io.assay.support.Maps` + `Keys` 常量，把 `Values` 提升可见性，统一 `asMap/asList/intValue`；把两套存储的判定逻辑抽成共享方法并加跨后端参数化测试。
3. 然后**收紧门禁**：Checkstyle 补 `CustomImportOrder` / `MissingJavadocType` / 命名规则，行长从 200 收到 120；同步修正注释与 README 的数据库支持说明。
4. 最后**拆大类**：CLI 按命令族拆分、`EvaluationEngine` 暴露 Builder；整理集成测试模块归属。

---

## 8. 附录：统计口径

| 指标 | 口径 |
|---|---|
| 193 / 133 / 60 | `git ls-files '*.java'`，再按路径含 `/src/main/`、`/src/test/` 过滤 |
| 21,510 行 | 仅主代码 `wc -l` 求和 |
| 120 文件含 `Map<String,Object>` | `git ls-files '*.java' \| xargs grep -l` |
| 41 / 13 unchecked | 主 / 测试代码中 `@SuppressWarnings("unchecked")` 出现次数 |
| 35 / 9 helper | 主 / 测试代码中 `private static ... (asMap\|asList\|intValue)(` 定义数 |
| 45 处 `System.out/err` | 主代码，全部位于 `assay-cli` |
| 14 个 >500 行文件 | `git ls-files '*.java' \| xargs wc -l` |
| 0 error / 0 bug | 各模块 `target/checkstyle-result.xml` 的 `<error>`、`target/spotbugsXml.xml` 的 `<BugInstance>` |
| 346 测试 / 82.1% | `**/target/surefire-reports/*.txt`、`app/target/site/jacoco-aggregate/jacoco.csv`（完整测试快照） |

> 所有搜索均排除 `target/` 与 `.assay/`（后者是被忽略的本地构建副本，含 115 个重复 Java 文件，纳入统计会显著放大数字）。

---

## 9. 整改记录（2026-11-11）

本轮整改后新增 6 个 Java 文件（`CaseChecks.java`、`CaseChecksTest.java`、`SecurityStartupCheck.java`、`execution/Stores.java`、`AuditScopeCommand.java`、`QuickstartCommand.java`）与第 14 个模块 `assay-integration-tests`（迁入 10 个跨模块测试），删除 `cli/Stores.java`；共 130+ 个文件变更（含 import 排序与 120 列换行）。验证命令：

```bash
mvn -B -o -q verify
```

结果：退出码 0；**363 个测试**（其中 80 个在 `assay-integration-tests`；0 failure / 0 error / 1 skipped）；聚合覆盖率 **82.1% 行 / 66.0% 分支**（`app` 以 test scope 依赖集成模块，故其覆盖数据进入聚合，且不会打进发行 jar）；Checkstyle 0 error、SpotBugs 0 bug。

| 文档项 | 状态 | 实现 |
|---|---|---|
| F1 零日志 | ✅ 已完成 | `JobRunQueue` 用 `System.Logger` 记录轮询/失败/心跳；`RunService` 用 SLF4J 记录 5 处降级，且 `get()` 在 job store 故障时仍返回持久化记录；`RunNotifier` 的 10 个观察者异常改为 DEBUG |
| N4 心跳语义 | ✅ 已完成 | `heartbeat()` 返回 `false`（租约丢失）才停止 worker；抛异常只记 WARN 并等下一 tick；`JobStore.heartbeat` 补契约 Javadoc |
| N1 限额不一致 | ✅ 已完成 | `JobStore.DEFAULT_LIST_LIMIT=200` / `MAX_LIST_LIMIT=500`；两个 store 统一用 `MAX_LIST_LIMIT`；`JobRunQueue.findJobByRunId` 改为 `JobStore.findByRunId`；文件存储新增 `run-index/` 指针目录，JDBC 新增 `run_id` 列 + 索引；重复 run_id 时文件索引与回退均取最新 job；`reindex-jobs` 支持文件指针重建和 JDBC 旧行 `run_id` 回填；新增查询/重建/回填测试 |
| F3 鉴权部署风险 | ✅ 已完成 | 新增 `SecurityStartupCheck`：token 为空且绑定非 loopback 时启动 WARN；设置 `assay.server.fail-on-insecure-binding=true` 时改为拒绝启动 |
| F4 弱类型 helper | ✅ 已完成 | `Values` 提升为 public，新增 `toInt/numberAsInt/numberAsDouble/asMap/asList`；各模块复制实现统一委托到 `Values`，并移除 `EvaluationEngine`/`AutoEvolutionLoop`/`ResultStore`/`AssayCli` 中的重复方法 |
| F5 存储规则重复 | ✅ 已完成 | 新增 `CaseChecks.apply()` 供文件与 JDBC 后端共用，并加单元测试 |
| F2 JDBC 方言 | ✅ 已完成 | `JdbcJobStore.list` 的 `limit` 改为 `?` 参数；schema 迁移改为 `DatabaseMetaData` 检测列/索引后执行普通 `ALTER/CREATE INDEX`，避免 MySQL 不支持的 `IF NOT EXISTS`；注释与 README 明确 H2/MySQL/PostgreSQL 兼容范围（MySQL 仍无 CI 覆盖） |
| N3 全限定名 | ✅ 已完成 | 主代码非 import 的 `java.*` FQN 从 76 处降到 **0**；同时清理未使用 import |
| N5 CLI/Store 重复 | ✅ 已完成 | `serve`/`worker` 的队列选项抽成 `queueOptions()`；store 工厂下沉到 `io.assay.execution.Stores`，CLI 与 `RunService` 共用 |
| N2 Dashboard 注释 | ✅ 已完成 | 类注释说明默认 GET-only、`--worker` 才启用 job POST |
| F8 测试归属 | ✅ 已完成 | 10 个跨模块测试迁入新模块 `assay-integration-tests`（第 14 个模块）；`app` 以 **test scope** 依赖它，覆盖数据进入聚合但不会打进发行 jar；README 已说明 |
| F6 上帝类 | ✅ 本轮完成 | 命令族已拆到 `QueueCommands`/`EvolutionCommands`/`CompareCommand`/`ReviewCommands`/`AuditScopeCommand`/`QuickstartCommand`，`AssayCli` 1535 → **440 行**（只保留命令表、分发和共享的引擎/输出辅助）；`EvaluationSettings` 改为 public + Builder |
| F7 门禁收紧 | ✅ 本轮完成 | 启用 `CustomImportOrder`、命名规则、`MissingJavadocType`、`CyclomaticComplexity(30)`、`NPathComplexity(1,000,000)`；行长 200 → **120**。为达标拆分了 `EvolutionPolicyEvaluator.decide`、`EvaluationEngine.runSuiteLocked`、`AuditScopeCommand.run`、`CompareGate.evaluate`、`ValueOps.equal`、`Reporting` 两个报表构建方法、`RuleConfig.parse`；`ScopeAudit.auditScope` 与 `AutoEvolutionLoop.runLocked` 作为编排方法保留并同时抑制 CC/NPath。NPath 下一步可收到 100k（`OpenAiTrace` 670k、`ReviewSamples` 331k、`TraceFileAdapter` 276k、`TestSelectionService` 158k 仍偏高） |
| F9/F10 提示项 | ✅ | Dockerfile 顶部注明只构建 CLI 发行包；README 覆盖率与测试约定更新 |

**行为边界**：两个后端都已优先走索引。文件存储的旧数据回退会扫描全部 job 并取 `created_at` 最新者（慢但正确）；JDBC 旧行 `run_id` 为空时仍回退到 `MAX_LIST_LIMIT=500` 的列表扫描，**执行 `reindex-jobs` 后**旧行会被回填，不再受限。文件存储的索引指针在 `submit` 时写入、`prune` 时清理；`reindex-jobs` 对文件 store 重建 `run-index/`，对 JDBC store 回填 `run_id` 列。CLI `worker` 已改为在存储异常时记录错误并继续，而不是退出整个 worker 循环。

---

## 10. 前端重构记录（2026-11-11）

依据 `maison-des-mots-art-direction`（奶白/银蓝/墨蓝纸雕、模切白边、书签菜单、纸页转场）重构 `assay-dashboard`：

- 新增 `paper.css`：书壳/内框/模切卡片、右侧书签、≤1080 底部纸质 tab、纸张颗粒、四角花线、`page-enter` 入场动画。
- `index.html` 改为 `book-shell → inner-frame → masthead/page-stage/colophon` 骨架；保留 `app.js` 依赖的全部 ID（`view`、`navigation`、`section-label`、`theme-select`、`search`、`refresh-time`、`source-path` 等）。
- `theme.css` 换成奶白/银蓝/墨蓝 token；深色主题保留为「夜纸」变体；字体使用系统衬线栈回退，纹理/花角全部由 CSS/SVG 生成，**无外部字体或图片请求**。
- `app.js` 在 section 变化时触发 `page-enter`，`prefers-reduced-motion` 下自动禁用。
- `DashboardServer` 静态资源表新增 `/paper.css`；`DashboardServerTest` 断言其返回 200。
- 截图验收：Chrome headless 1440px（浅/深色）、768px、500px；`node --test` 17 项通过；`mvn verify` 364 测试、82.1% 行 / 66.0% 分支、Checkstyle/SpotBugs 0。

### 上手引导

- 新增 `demo` 命令：`java -jar app/target/assay.jar demo` 会用内置 EchoAdapter 跑一条示例评测，再启动带任务队列的 Dashboard 并打开浏览器；`--no-open` / `--no-serve` 可拆分使用。
- `assay.cmd` 与 `sh ./assay` 现在都委托给 `demo` 命令，不再各自重复 run + serve 逻辑。
- 总览页新增可折叠的「三步开始」卡片：运行 `demo`、查看 demo 运行、进入「开始评测」接入自己的 Agent；没有报告时默认展开。
- README 增加「任意平台一条命令体验」和命令表 `demo` 行。
- 新增 `docs/dashboard-guide.md`：按页面用大白话解释总览、开始评测、任务队列、运行记录、版本对比、版本演化、自动演化、范围审计、发布验证；README、QUICKSTART、demo-runbook 均已链接。
- 结果展示增强：运行详情新增顶部结论卡（通过/告警/失败 + 首个失败用例、被拒工具、交接、重复步骤）；场景/规则/测试集通过率增加进度条；含 `multi_agent` 的报告新增「多智能体」页签（总步骤、模型/工具调用、交接、被拒工具、最长重复、Agent 列表）。
