# W-bot Java (AgentScope Java)

这是按 `W-bot` Python 版本能力做的一版 Java 实现，框架为 `agentscope-java`。

## 功能对齐

- CLI 模式（会话自动恢复、`/new` 新会话）
- Feishu 网关模式（事件回调接收、消息过滤、自动回复）
- `MEMORY.MD` 长期记忆（检索 + 保存）
- 内置工具集：`read_file/write_file/edit_file/list_dir/web_fetch/web_search/message/spawn/save_memory`
- 可选工具：`exec`（本地命令）、`cron`（文件化任务记录）、`mcp_call`
- 统一配置文件：`src/main/resources/application.yml`

## 环境要求

- JDK 17+
- Maven 3.8+

## 快速开始

1. 填写 `src/main/resources/application.yml`：

- `agent.dashscopeApiKey`
- `channels.feishu.appId`
- `channels.feishu.appSecret`

3. 编译：

```bash
mvn -q -DskipTests compile
```

4. 启动 CLI：

```bash
mvn -q exec:java -Dexec.args="cli"
```

5. 启动 Feishu 网关：

```bash
mvn -q exec:java -Dexec.args="feishu"
```

## 入口说明

- 主类：`com.wbot.Main`
- 默认模式：`cli`
- 默认配置：`classpath:application.yml`
- 参数：`--config <path>`（也支持 `--config classpath:application.yml`）
