# Assay 快速开始

不需要懂 Java。你只需要一个能返回 JSON 的 Agent HTTP 地址。

## 1. 安装 Java 17

只安装一次，之后不用管 Maven。

- Windows / macOS：安装 Eclipse Temurin 17
- 确认：打开终端输入 `java -version`，能看到 17 即可

## 2. 启动

### Windows

双击 `assay.cmd`。

### macOS / Linux

在解压目录运行：

```bash
sh ./assay
```

第一次会打开一个本地页面，并跑一条内置示例，不需要 API Key。

## 3. 评测自己的 Agent

在页面点击 **开始评测**：

1. 选择 **HTTP 接口**
2. 填 Agent 地址，例如 `http://127.0.0.1:9000/agent`
3. 填答案字段，例如 `data.answer`
4. 填期望关键字，例如 `hello`

页面会生成两个文件：

```text
my-agent.http.json
http-cases.jsonl
```

按页面提示复制运行命令即可。运行完成后点顶部「刷新」看报告。

## 更省事：一条命令

如果不想下载文件，在项目根目录运行：

```bash
# Windows
assay.cmd quickstart

# macOS / Linux
sh ./assay quickstart
```

它只问三个问题：

```text
Agent HTTP endpoint:
JSON field containing the answer:
Expected keyword in the answer:
```

然后自动生成配置和用例、运行评测、打开报告。

## 想让报告看到工具步骤

让 Agent 在响应里返回 `events`：

```json
{
  "output": {"answer": "hello"},
  "events": [
    {"module": "Router", "action": "route", "fields": {"route": "ECHO"}},
    {"module": "Tool", "action": "search", "fields": {"query": "hello"}},
    {"module": "Tool", "action": "answer", "fields": {"answer": "hello"}}
  ]
}
```

在 HTTP 配置里加：

```json
"events_path": "events"
```

这样运行页就能看到每一步，而不只是最终答案。

## 比较两个版本

同一个 suite 跑两次，给第二次一个固定的运行 ID：

```bash
assay.cmd run --adapter my-agent.http.json --suite my-agent --cases http-cases.jsonl --run-id v1
assay.cmd run --adapter my-agent-v2.http.json --suite my-agent --cases http-cases.jsonl --run-id v2
assay.cmd compare --baseline v1 --candidate v2 --profile strict
```

报告会直接给出 `PASS`、`WARNING` 或 `BLOCK`，并列出退化的用例和第一个分歧步骤。

## 不要一开始做什么

- 不需要写 Java
- 不需要配 JDBC
- 不需要跑 10,000 条
- 不需要懂 `ProjectAdapter`、`rule_stats`、`--workers`

先跑通 3 条用例，再逐步加规则和用例。

## 遇到问题

- 页面打不开：确认终端窗口没有关闭
- 请求失败：先用 curl/Postman 确认地址能返回 JSON
- 看不到步骤：检查 Agent 是否返回 `events`，配置是否填了 `events_path`
- 需要鉴权：使用环境变量，不要把密钥写进 JSON
