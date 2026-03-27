package com.wbot.agent;

import com.wbot.config.AppConfig;
import com.wbot.memory.LongTermMemoryStore;
import com.wbot.tools.RuntimeTools;
import com.wbot.util.MsgText;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CyberAgentService {
    private static final Logger log = LoggerFactory.getLogger(CyberAgentService.class);
    private static final String SYSTEM_PROMPT = "你是 CyberCore CLI Agent。"
            + "你需要优先给出清晰、可执行的答案。"
            + "当任务需要精确计算、脚本验证或数据处理时，可以调用工具。"
            + "当用户偏好、长期事实或关键经验值得保留时，调用 save_memory。"
            + "工具调用参数必须严格匹配 schema。";

    private final AppConfig config;
    private final LongTermMemoryStore memoryStore;
    private final Session session;
    private final Path workspaceRoot;
    private final Map<String, ReActAgent> agentBySession = new ConcurrentHashMap<>();

    public CyberAgentService(AppConfig config, Path workspaceRoot) {
        this.config = config;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.memoryStore = new LongTermMemoryStore(config.agent.memoryFilePath);
        Path sessionPath = Path.of(System.getProperty("user.home"), ".w-bot-java", "sessions");
        this.session = new JsonSession(sessionPath);
    }

    public String ask(String sessionId, String userText) {
        ReActAgent agent = agentBySession.computeIfAbsent(sessionId, this::buildAndLoadAgent);
        String memoryContext = buildMemoryContext(userText);
        String enriched = memoryContext.isBlank()
                ? userText
                : "已检索到的长期记忆:\n" + memoryContext + "\n\n用户问题:\n" + userText;

        Msg input = Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(enriched).build())
                .build();

        Msg output = agent.call(input).block();
        agent.saveTo(session, sessionId);
        String text = MsgText.extract(output);
        return text == null || text.isBlank() ? "我收到了你的消息，但暂时没有生成可用回复。" : text;
    }

    public String newSessionId() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("cli_session_yyyyMMdd_HHmmss"));
    }

    public void clearCachedSession(String sessionId) {
        agentBySession.remove(sessionId);
    }

    private ReActAgent buildAndLoadAgent(String sessionId) {
        Toolkit toolkit = RuntimeTools.buildToolkit(config.agent, memoryStore, config.agent.userId, workspaceRoot);
        ReActAgent agent = ReActAgent.builder()
                .name("CyberCore")
                .sysPrompt(SYSTEM_PROMPT)
                .model(buildChatModel())
                .memory(new InMemoryMemory())
                .toolkit(toolkit)
                .build();

        if (session.exists(SimpleSessionKey.of(sessionId))) {
            agent.loadFrom(session, sessionId);
        }
        return agent;
    }

    private ChatModelBase buildChatModel() {
        String baseUrl = config.agent.bailianBaseUrl == null ? "" : config.agent.bailianBaseUrl.trim();
        String modelName = config.agent.bailianModelName == null ? "" : config.agent.bailianModelName.trim();
        boolean compatibleMode = baseUrl.contains("/compatible-mode/");

        if (compatibleMode) {
            log.info("Using OpenAI-compatible Bailian endpoint: baseUrl={}, model={}", baseUrl, modelName);
            return OpenAIChatModel.builder()
                    .apiKey(config.agent.dashscopeApiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .stream(false)
                    .formatter(new OpenAIChatFormatter())
                    .build();
        }

        log.info("Using native DashScope endpoint: baseUrl={}, model={}", baseUrl, modelName);
        return DashScopeChatModel.builder()
                .apiKey(config.agent.dashscopeApiKey)
                .baseUrl(baseUrl.isBlank() ? "https://dashscope.aliyuncs.com" : baseUrl)
                .modelName(modelName)
                .stream(false)
                .enableThinking(false)
                .formatter(new DashScopeChatFormatter())
                .build();
    }

    private String buildMemoryContext(String userText) {
        int topK = Math.max(1, config.agent.retrieveTopK);
        var docs = memoryStore.retrieve(userText, topK);
        if (docs.isEmpty()) {
            docs = memoryStore.retrieveRecent(topK);
        }
        if (docs.isEmpty()) {
            return "";
        }
        return docs.stream().map(d -> "- " + d).reduce((a, b) -> a + "\n" + b).orElse("");
    }
}
