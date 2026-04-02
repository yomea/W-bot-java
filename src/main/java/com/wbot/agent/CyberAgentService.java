package com.wbot.agent;

import com.wbot.config.AppConfig;
import com.wbot.memory.LongTermMemoryStore;
import com.wbot.session.SessionTranscriptStore;
import com.wbot.skills.SkillsLoader;
import com.wbot.subagent.SubagentManager;
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
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CyberAgentService {
    private static final Logger log = LoggerFactory.getLogger(CyberAgentService.class);
    private static final String BASE_SYSTEM_PROMPT = "你是 CyberCore CLI Agent。"
            + "你需要优先给出清晰、可执行的答案。"
            + "当任务需要精确计算、脚本验证或数据处理时，可以调用工具。"
            + "当用户偏好、长期事实或关键经验值得保留时，调用 save_memory。"
            + "工具调用参数必须严格匹配 schema。"
            + "如果存在匹配的 skill，优先使用 list_skills/read_skill 理解它；只有用户明确要求按 skill 执行时，再调用 run_skill。";

    private final AppConfig config;
    private final LongTermMemoryStore memoryStore;
    private final SessionTranscriptStore transcriptStore;
    private final SkillsLoader skillsLoader;
    private final SubagentManager subagentManager;
    private final Session session;
    private final Path workspaceRoot;
    private final Map<String, ReActAgent> agentBySession = new ConcurrentHashMap<>();
    private final Map<String, SessionUsage> usageBySession = new ConcurrentHashMap<>();

    public CyberAgentService(AppConfig config, Path workspaceRoot) {
        this.config = config;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.memoryStore = new LongTermMemoryStore(config.agent.memoryFilePath);
        this.transcriptStore = new SessionTranscriptStore(
                Path.of(System.getProperty("user.home"), ".w-bot-java", "transcripts"));
        this.skillsLoader = config.agent.enableSkills
                ? new SkillsLoader(config.agent.skillsWorkspaceDir, config.agent.skillsBuiltinDir)
                : null;
        this.subagentManager = new SubagentManager(this.workspaceRoot, this::runSubagentJob);
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
        String reply = text == null || text.isBlank() ? "我收到了你的消息，但暂时没有生成可用回复。" : text;
        usageBySession.compute(sessionId, (key, current) -> mergeUsage(current, estimateUsage(output, enriched, reply)));
        transcriptStore.append(sessionId, "user", userText);
        transcriptStore.append(sessionId, "assistant", reply);
        return reply;
    }

    public String newSessionId() {
        return "cli_session_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    public void clearCachedSession(String sessionId) {
        agentBySession.remove(sessionId);
    }

    public java.util.List<SessionTranscriptStore.SessionMessage> history(String sessionId) {
        return transcriptStore.list(sessionId);
    }

    private ReActAgent buildAndLoadAgent(String sessionId) {
        Toolkit toolkit = RuntimeTools.buildToolkit(config.agent, memoryStore, config.agent.userId, workspaceRoot, subagentManager, true);
        ReActAgent agent = ReActAgent.builder()
                .name("CyberCore")
                .sysPrompt(buildSystemPrompt())
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

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder(BASE_SYSTEM_PROMPT);
        if (skillsLoader == null) {
            return sb.toString();
        }
        List<String> always = skillsLoader.getAlwaysSkills().stream().map(SkillsLoader.SkillSpec::name).toList();
        String alwaysContent = always.isEmpty() ? "" : skillsLoader.loadSkillsForContext(always);
        String summary = skillsLoader.buildSkillsSummary();
        if (!alwaysContent.isBlank()) {
            sb.append("\n\n---\n\n# Active Skills\n\n").append(alwaysContent);
        }
        if (!summary.isBlank()) {
            sb.append("\n\n---\n\n# Skills\n\n")
                    .append("The following skills extend your capabilities. Prefer reading a matching skill before broad changes.\n")
                    .append("Use run_skill only when the user explicitly asks to use a skill or when a skill-specific execution brief is needed.\n\n")
                    .append(summary);
        }
        return sb.toString();
    }

    public List<Map<String, Object>> listSubagents(String status, int limit) {
        return subagentManager.list(status, limit);
    }

    public Map<String, Object> waitForSubagent(String id, int timeoutSeconds) {
        return subagentManager.waitFor(id, timeoutSeconds);
    }

    public List<SkillsLoader.SkillSpec> listSkills() {
        if (skillsLoader == null) {
            return List.of();
        }
        return skillsLoader.listSkills(false);
    }

    public SkillsLoader.SkillSpec getSkill(String name) {
        if (skillsLoader == null) {
            return null;
        }
        return skillsLoader.getSkill(name);
    }

    public SkillsLoader.SkillRequirementCheck checkSkillRequirements(SkillsLoader.SkillSpec skill) {
        if (skillsLoader == null || skill == null) {
            return new SkillsLoader.SkillRequirementCheck(false, List.of(), List.of());
        }
        return skillsLoader.checkRequirements(skill);
    }

    public SessionUsage getSessionUsage(String sessionId) {
        SessionUsage usage = usageBySession.get(sessionId);
        if (usage != null) {
            return usage;
        }
        List<SessionTranscriptStore.SessionMessage> history = transcriptStore.list(sessionId);
        int input = 0;
        int output = 0;
        for (SessionTranscriptStore.SessionMessage message : history) {
            int estimate = roughTokenEstimate(message.content());
            if ("user".equalsIgnoreCase(message.role())) {
                input += estimate;
            } else if ("assistant".equalsIgnoreCase(message.role())) {
                output += estimate;
            }
        }
        SessionUsage rebuilt = new SessionUsage(input, output, 0, 0, input + output, estimateCost(input, output, 0, 0));
        usageBySession.put(sessionId, rebuilt);
        return rebuilt;
    }

    private String runSubagentJob(SubagentManager.SubagentJob job) {
        ReActAgent agent = buildSubagent(job);
        String prompt = buildSubagentTaskPrompt(job);
        Msg input = Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(prompt).build())
                .build();
        Msg output = agent.call(input).block();
        return MsgText.extract(output);
    }

    private ReActAgent buildSubagent(SubagentManager.SubagentJob job) {
        Toolkit toolkit = RuntimeTools.buildToolkit(config.agent, memoryStore, config.agent.userId, workspaceRoot, subagentManager, false);
        return ReActAgent.builder()
                .name("CyberCoreSubagent")
                .sysPrompt(buildSubagentSystemPrompt(job))
                .model(buildChatModel())
                .memory(new InMemoryMemory())
                .toolkit(toolkit)
                .build();
    }

    private String buildSubagentSystemPrompt(SubagentManager.SubagentJob job) {
        return """
                你是 W-bot Java 的后台子 Agent。
                你只负责完成当前分配的独立子任务，并向主 Agent 返回精炼结果。
                不要把工作再委派给新的子 Agent。
                尽量直接产出结论、实现结果、风险和后续建议。
                子任务类型: %s
                子任务标签: %s
                """.formatted(job.agentType(), job.label().isBlank() ? "-" : job.label());
    }

    private String buildSubagentTaskPrompt(SubagentManager.SubagentJob job) {
        return """
                请完成下面这个子任务，并只返回对主 Agent 有用的结果摘要。

                任务:
                %s
                """.formatted(job.task());
    }

    private SessionUsage estimateUsage(Msg output, String inputText, String replyText) {
        ChatUsage usage = output == null ? null : output.getChatUsage();
        if (usage != null && (usage.getInputTokens() > 0 || usage.getOutputTokens() > 0)) {
            int inputTokens = Math.max(0, usage.getInputTokens());
            int outputTokens = Math.max(0, usage.getOutputTokens());
            return new SessionUsage(
                    inputTokens,
                    outputTokens,
                    0,
                    0,
                    Math.max(0, usage.getTotalTokens()),
                    estimateCost(inputTokens, outputTokens, 0, 0));
        }
        int inputTokens = roughTokenEstimate(inputText);
        int outputTokens = roughTokenEstimate(replyText);
        return new SessionUsage(inputTokens, outputTokens, 0, 0, inputTokens + outputTokens, estimateCost(inputTokens, outputTokens, 0, 0));
    }

    private SessionUsage mergeUsage(SessionUsage current, SessionUsage delta) {
        if (current == null) {
            return delta;
        }
        int input = current.inputTokens() + delta.inputTokens();
        int output = current.outputTokens() + delta.outputTokens();
        int cacheWrite = current.cacheCreationInputTokens() + delta.cacheCreationInputTokens();
        int cacheRead = current.cacheReadInputTokens() + delta.cacheReadInputTokens();
        return new SessionUsage(input, output, cacheWrite, cacheRead, input + output + cacheWrite + cacheRead, estimateCost(input, output, cacheWrite, cacheRead));
    }

    private static int roughTokenEstimate(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isBlank()) {
            return 0;
        }
        return Math.max(1, normalized.length() / 4);
    }

    private static double estimateCost(int input, int output, int cacheWrite, int cacheRead) {
        double inputRate = envRate("WBOT_INPUT_COST_PER_1M");
        double outputRate = envRate("WBOT_OUTPUT_COST_PER_1M");
        double cacheWriteRate = envRate("WBOT_CACHE_WRITE_COST_PER_1M");
        double cacheReadRate = envRate("WBOT_CACHE_READ_COST_PER_1M");
        return (input * inputRate + output * outputRate + cacheWrite * cacheWriteRate + cacheRead * cacheReadRate) / 1_000_000.0;
    }

    private static double envRate(String key) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return 0.0;
        }
        try {
            return Math.max(0.0, Double.parseDouble(raw.trim()));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
