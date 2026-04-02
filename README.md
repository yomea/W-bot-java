# W-bot Java (AgentScope Java)

这是按 `W-bot` Python 版本能力做的一版 Java 实现，框架为 `agentscope-java`。

## 功能对齐

- CLI 模式（会话自动恢复、`/new` 新会话）
- Web 聊天页与 HTTP API
- Feishu 网关模式（事件回调接收、消息过滤、自动回复）
- `MEMORY.MD` 长期记忆（检索 + 保存）
- Skill 机制（内置 + 工作区 Skills）
- 显式子任务协作（`spawn` / `list_subagents` / `wait_subagent`）
- CLI 流式输出体验（思考中状态 + 增量打印）
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

5. 启动 Web：

```bash
mvn -q exec:java -Dexec.args="web"
```

然后访问：

`http://127.0.0.1:8000`

6. 启动 Feishu 网关：

```bash
mvn -q exec:java -Dexec.args="feishu"
```

## 入口说明

- 主类：`com.wbot.Main`
- 默认模式：`cli`
- 默认配置：`classpath:application.yml`
- 可选模式：`cli` / `web` / `feishu`
- 参数：`--config <path>`（也支持 `--config classpath:application.yml`）

## 第二批已对齐能力

- 启用 `agent.enableSkills=true` 后，会自动扫描内置技能目录和工作区 `skills/`
- 内置工具新增：`list_skills`、`read_skill`、`run_skill`
- 系统提示会自动注入 skills 摘要，`always: true` 的 skill 会直接注入上下文
- 当前内置技能示例：`project-analyzer`、`clawhub`

## 第三批已对齐能力

- `spawn` 不再只是写记录，而是会真正启动后台子任务
- 可用 `list_subagents` 查看任务状态
- 可用 `wait_subagent` 等待子任务完成并拿到结果
- 子任务默认禁用再次拉起子任务，避免无限递归

## CLI 命令

- `/help`
- `/new [session_id]`
- `/resume <session_id>`
- `/session`
- `/sessions`
- `/status`
- `/tasks [status|task_id]`
- `/history [count]`
- `/stats`
- `/cost`
- `/config`
- `/skills [skill_name]`
- `/clear`
- `/exit`
- `/quit`

## CLI 流式输出

- `agent.enableStreaming=true` 时，CLI 会先显示思考中的动态状态
- 生成完成后会按块增量打印回复，而不是一次性整段输出
- 当前是兼容式流输出，先对齐交互体验，后续可继续升级为模型原生 token 级流式

## CLI 运行状态

- 最近会话列表会展示最近 phase、最近动作、任务数量和错误摘要
- 启动 CLI 或恢复会话时，会自动打印最近几条上下文预览
- `/status` 会显示 phase、任务高亮、token 统计和成本估算
- `/stats` 会显示消息数、字符数和 token 统计
- `/cost` 会优先基于模型返回的 usage 统计，缺失时回退到本地估算
- 成本估算使用这些环境变量：`WBOT_INPUT_COST_PER_1M`、`WBOT_OUTPUT_COST_PER_1M`、`WBOT_CACHE_WRITE_COST_PER_1M`、`WBOT_CACHE_READ_COST_PER_1M`
