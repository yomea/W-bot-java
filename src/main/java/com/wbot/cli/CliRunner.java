package com.wbot.cli;

import com.wbot.agent.CyberAgentService;
import com.wbot.agent.SessionUsage;
import com.wbot.config.AppConfig;
import com.wbot.session.SessionTranscriptStore;
import com.wbot.skills.SkillsLoader;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class CliRunner {
    private final AppConfig config;
    private final CyberAgentService service;
    private final Path workspaceRoot;
    private final RuntimeStatusSnapshot runtimeStatus;

    public CliRunner(AppConfig config, Path workspaceRoot) {
        this.config = config;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.service = new CyberAgentService(config, workspaceRoot);
        this.runtimeStatus = new RuntimeStatusSnapshot(config.agent.sessionId);
    }

    public void run() {
        System.out.println("W-bot Java CLI | type /help for commands");
        SessionStateStore sessionStore = new SessionStateStore(config.agent.sessionStateFilePath);
        String currentSessionId = sessionStore.load();
        if (currentSessionId == null || currentSessionId.isBlank()) {
            currentSessionId = config.agent.sessionId;
        }
        runtimeStatus.setSession(currentSessionId);
        persistSessionState(sessionStore, currentSessionId, "");
        System.out.println("Current session: " + currentSessionId + "  (/new, /resume, /sessions)");
        printSessionRestorePreview(currentSessionId);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                System.out.print("\nYou > ");
                String userText = reader.readLine();
                if (userText == null) {
                    return;
                }
                userText = userText.trim();
                if (userText.isBlank()) {
                    continue;
                }
                if (userText.startsWith("/")) {
                    String outcome = handleSlashCommand(userText, currentSessionId, sessionStore);
                    if ("__EXIT__".equals(outcome)) {
                        System.out.println("Session closed.");
                        return;
                    }
                    if (outcome.startsWith("__SESSION__:")) {
                        currentSessionId = outcome.substring("__SESSION__:".length());
                        runtimeStatus.setSession(currentSessionId);
                        printSessionRestorePreview(currentSessionId);
                    } else if (!outcome.isBlank()) {
                        System.out.println(outcome);
                    }
                    continue;
                }

                if (config.agent.enableStreaming) {
                    renderStreamingReply(currentSessionId, userText, sessionStore);
                } else {
                    runtimeStatus.setPhase("analyzing", "分析上下文中", "准备处理用户请求");
                    String reply = service.ask(currentSessionId, userText);
                    runtimeStatus.setPhase("idle", "空闲", "回复完成");
                    persistSessionState(sessionStore, currentSessionId, summarizeTitle(userText));
                    System.out.println("\nAgent > " + reply);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("CLI runtime failed", e);
        }
    }

    private String handleSlashCommand(String userText, String currentSessionId, SessionStateStore sessionStore) {
        String[] parts = userText.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";
        return switch (command) {
            case "/help" -> """
                    Commands:
                    /new [session_id]
                    /resume <session_id>
                    /session
                    /sessions
                    /status
                    /tasks [status|task_id]
                    /history [count]
                    /stats
                    /cost
                    /config
                    /skills [skill_name]
                    /clear
                    /exit
                    /quit
                    """.trim();
            case "/new" -> {
                String nextSessionId = arg.isBlank() ? service.newSessionId() : arg;
                service.clearCachedSession(nextSessionId);
                runtimeStatus.setSession(nextSessionId);
                runtimeStatus.setPhase("idle", "空闲", "已创建新会话");
                persistSessionState(sessionStore, nextSessionId, "");
                yield "__SESSION__:" + nextSessionId;
            }
            case "/resume" -> {
                if (arg.isBlank()) {
                    yield "Usage: /resume <session_id>";
                }
                runtimeStatus.setSession(arg);
                runtimeStatus.setPhase("idle", "空闲", "已恢复会话");
                persistSessionState(sessionStore, arg, "");
                yield "__SESSION__:" + arg;
            }
            case "/session" -> "Current session: " + currentSessionId;
            case "/sessions" -> renderSessions(sessionStore.listRecent(), currentSessionId);
            case "/status" -> renderStatus(currentSessionId);
            case "/tasks" -> renderTasks(arg);
            case "/history" -> renderHistory(currentSessionId, arg);
            case "/stats" -> renderStats(currentSessionId);
            case "/cost" -> renderCost(currentSessionId);
            case "/config" -> renderConfig();
            case "/skills", "/skill" -> renderSkills(arg);
            case "/clear" -> {
                System.out.print("\033[H\033[2J");
                System.out.flush();
                yield "W-bot Java CLI | type /help for commands\nCurrent session: " + currentSessionId + "  (/new, /resume, /sessions)";
            }
            case "/exit", "/quit" -> "__EXIT__";
            default -> {
                if ("quit".equalsIgnoreCase(userText) || "exit".equalsIgnoreCase(userText)) {
                    yield "__EXIT__";
                }
                yield "Unknown command. Type /help.";
            }
        };
    }

    private String renderSessions(List<SessionStateStore.SessionRecord> sessions, String currentSessionId) {
        if (sessions.isEmpty()) {
            return "No recent sessions.";
        }
        StringBuilder sb = new StringBuilder("Recent sessions:");
        for (SessionStateStore.SessionRecord session : sessions) {
            sb.append("\n- ")
                    .append(session.sessionId());
            if (session.sessionId().equals(currentSessionId)) {
                sb.append(" [current]");
            }
            if (!session.updatedAt().isBlank()) {
                sb.append(" | ").append(session.updatedAt());
            }
            if (!session.lastPhase().isBlank()) {
                sb.append(" | ").append(session.lastPhase());
            }
            if (session.taskCount() > 0) {
                sb.append(" | tasks=").append(session.taskCount());
            }
            if (!session.title().isBlank()) {
                sb.append("\n  title: ").append(session.title());
            }
            if (!session.lastAction().isBlank()) {
                sb.append("\n  last_action: ").append(shorten(session.lastAction(), 100));
            }
            if (!session.lastError().isBlank()) {
                sb.append("\n  last_error: ").append(shorten(session.lastError(), 100));
            }
        }
        return sb.toString();
    }

    private String renderStatus(String currentSessionId) {
        List<Map<String, Object>> jobs = service.listSubagents("", 20);
        runtimeStatus.refreshTasks(jobs);
        SessionUsage usage = service.getSessionUsage(currentSessionId);
        runtimeStatus.updateUsage(usage.inputTokens(), usage.outputTokens(), usage.estimatedCostUsd());
        StringBuilder sb = new StringBuilder("""
                Runtime Status
                - session: %s
                - workspace: %s
                - phase: %s
                - recent_action: %s
                - tasks: running=%s pending=%s completed=%s failed=%s
                - tokens: input=%s output=%s total=%s
                - skills: %s
                - exec_tool: %s
                - cron_tool: %s
                """.formatted(
                currentSessionId,
                workspaceRoot,
                runtimeStatus.phaseLabel,
                runtimeStatus.recentAction.isBlank() ? "[none]" : runtimeStatus.recentAction,
                runtimeStatus.tasks.running,
                runtimeStatus.tasks.pending,
                runtimeStatus.tasks.completed,
                runtimeStatus.tasks.failed,
                runtimeStatus.inputTokens,
                runtimeStatus.outputTokens,
                runtimeStatus.totalTokens,
                config.agent.enableSkills ? service.listSkills().size() : 0,
                config.agent.enableExecTool,
                config.agent.enableCronService).trim());
        if (runtimeStatus.totalCost > 0) {
            sb.append("\n- cost: $").append(String.format(java.util.Locale.US, "%.6f", runtimeStatus.totalCost)).append(" USD");
        }
        if (!runtimeStatus.lastError.isBlank()) {
            sb.append("\n- last_error: ").append(runtimeStatus.lastErrorPhase).append(" | ").append(runtimeStatus.lastError);
        }
        if (!runtimeStatus.tasks.highlightedTasks.isEmpty()) {
            sb.append("\n- highlighted_tasks:");
            for (String item : runtimeStatus.tasks.highlightedTasks) {
                sb.append("\n  - ").append(item);
            }
        }
        return sb.toString();
    }

    private String renderTasks(String arg) {
        String query = arg == null ? "" : arg.trim().toLowerCase();
        List<Map<String, Object>> jobs = service.listSubagents("", 20);
        if (!query.isBlank()) {
            jobs = jobs.stream()
                    .filter(job -> query.equals(String.valueOf(job.get("status")).toLowerCase())
                            || String.valueOf(job.get("id")).toLowerCase().contains(query))
                    .toList();
        }
        if (jobs.isEmpty()) {
            return "No matching background tasks.";
        }
        StringBuilder sb = new StringBuilder("Background Tasks:");
        for (Map<String, Object> job : jobs) {
            String id = String.valueOf(job.getOrDefault("id", "-"));
            String label = String.valueOf(job.getOrDefault("label", job.getOrDefault("agent_type", "-")));
            String status = String.valueOf(job.getOrDefault("status", "-"));
            String task = shorten(String.valueOf(job.getOrDefault("task", "")), 90);
            String error = String.valueOf(job.getOrDefault("error", ""));
            String result = String.valueOf(job.getOrDefault("final_response", ""));
            sb.append("\n- ").append(statusMarker(status)).append(" ")
                    .append(id, 0, Math.min(8, id.length()))
                    .append(" [").append(status).append("] ")
                    .append(label);
            if (!task.isBlank()) {
                sb.append("\n  task: ").append(task);
            }
            if (!error.isBlank()) {
                sb.append("\n  error: ").append(shorten(error, 120));
            }
            if (!result.isBlank() && "completed".equalsIgnoreCase(status)) {
                sb.append("\n  result: ").append(shorten(result, 120));
            }
        }
        return sb.toString();
    }

    private String renderHistory(String sessionId, String arg) {
        int count = 10;
        if (!arg.isBlank()) {
            try {
                count = Math.max(1, Math.min(20, Integer.parseInt(arg)));
            } catch (NumberFormatException ignored) {
                return "Usage: /history [count]";
            }
        }
        List<SessionTranscriptStore.SessionMessage> messages = service.history(sessionId);
        if (messages.isEmpty()) {
            return "No stored history for session: " + sessionId;
        }
        int start = Math.max(0, messages.size() - count);
        StringBuilder sb = new StringBuilder("Recent history:");
        for (int i = start; i < messages.size(); i++) {
            SessionTranscriptStore.SessionMessage message = messages.get(i);
            sb.append("\n[")
                    .append(message.role())
                    .append("] ")
                    .append(message.content());
        }
        return sb.toString();
    }

    private String renderStats(String sessionId) {
        List<SessionTranscriptStore.SessionMessage> messages = service.history(sessionId);
        SessionUsage usage = service.getSessionUsage(sessionId);
        long userCount = messages.stream().filter(message -> "user".equalsIgnoreCase(message.role())).count();
        long assistantCount = messages.stream().filter(message -> "assistant".equalsIgnoreCase(message.role())).count();
        int charCount = messages.stream().mapToInt(message -> message.content() == null ? 0 : message.content().length()).sum();
        return """
                Session: %s
                Messages: total=%s user=%s assistant=%s
                Characters: total=%s
                Tokens: input=%s output=%s total=%s
                Stored history file-backed: true
                """.formatted(sessionId, messages.size(), userCount, assistantCount, charCount, usage.inputTokens(), usage.outputTokens(), usage.totalTokens()).trim();
    }

    private String renderCost(String sessionId) {
        SessionUsage usage = service.getSessionUsage(sessionId);
        return """
                Estimated Cost
                - session: %s
                - input_tokens: %s
                - output_tokens: %s
                - cache_write_tokens: %s
                - cache_read_tokens: %s
                - total_tokens: %s
                - estimated_cost_usd: $%s
                - rate_envs: WBOT_INPUT_COST_PER_1M / WBOT_OUTPUT_COST_PER_1M / WBOT_CACHE_WRITE_COST_PER_1M / WBOT_CACHE_READ_COST_PER_1M
                """.formatted(
                sessionId,
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheCreationInputTokens(),
                usage.cacheReadInputTokens(),
                usage.totalTokens(),
                String.format(java.util.Locale.US, "%.6f", usage.estimatedCostUsd())).trim();
    }

    private String renderConfig() {
        List<String> lines = new ArrayList<>();
        lines.add("Current Config");
        lines.add("- workspace: " + workspaceRoot);
        lines.add("- model: " + config.agent.bailianModelName);
        lines.add("- base_url: " + config.agent.bailianBaseUrl);
        lines.add("- memory_file: " + config.agent.memoryFilePath);
        lines.add("- session_state_file: " + config.agent.sessionStateFilePath);
        lines.add("- retrieve_top_k: " + config.agent.retrieveTopK);
        lines.add("- enable_streaming: " + config.agent.enableStreaming);
        lines.add("- enable_exec_tool: " + config.agent.enableExecTool);
        lines.add("- enable_cron_service: " + config.agent.enableCronService);
        lines.add("- enable_skills: " + config.agent.enableSkills);
        lines.add("- skills_workspace_dir: " + config.agent.skillsWorkspaceDir);
        lines.add("- skills_builtin_dir: " + config.agent.skillsBuiltinDir);
        lines.add("- web: " + config.channels.web.host + ":" + config.channels.web.port + " enabled=" + config.channels.web.enabled);
        lines.add("- feishu: port=" + config.channels.feishu.port + " enabled=" + config.channels.feishu.enabled);
        return String.join("\n", lines);
    }

    private String renderSkills(String arg) {
        if (!config.agent.enableSkills) {
            return "Skills are disabled by config.enableSkills=false";
        }
        if (arg != null && !arg.isBlank()) {
            SkillsLoader.SkillSpec skill = service.getSkill(arg.trim());
            if (skill == null) {
                return "Skill not found: " + arg.trim();
            }
            SkillsLoader.SkillRequirementCheck check = service.checkSkillRequirements(skill);
            StringBuilder sb = new StringBuilder();
            sb.append("Skill: ").append(skill.name())
                    .append("\nSource: ").append(skill.source())
                    .append("\nPath: ").append(skill.path())
                    .append("\nAvailable: ").append(check.available())
                    .append("\nAlways: ").append(skill.always());
            if (!check.missingBins().isEmpty()) {
                sb.append("\nMissing CLI: ").append(String.join(", ", check.missingBins()));
            }
            if (!check.missingEnv().isEmpty()) {
                sb.append("\nMissing ENV: ").append(String.join(", ", check.missingEnv()));
            }
            sb.append("\n\n").append(skill.content());
            return sb.toString();
        }
        List<SkillsLoader.SkillSpec> skills = service.listSkills();
        if (skills.isEmpty()) {
            return "No skills discovered.";
        }
        StringBuilder sb = new StringBuilder("Skills:");
        for (SkillsLoader.SkillSpec skill : skills) {
            SkillsLoader.SkillRequirementCheck check = service.checkSkillRequirements(skill);
            sb.append("\n- ").append(skill.name())
                    .append(" [").append(check.available() ? "available" : "blocked").append("]")
                    .append(" | ").append(skill.source());
            if (!skill.description().isBlank()) {
                sb.append(" | ").append(skill.description());
            }
        }
        return sb.toString();
    }

    private void renderStreamingReply(String currentSessionId, String userText, SessionStateStore sessionStore) {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> service.ask(currentSessionId, userText));
        String[] spinnerFrames = {"|", "/", "-", "\\"};
        int spinnerIndex = 0;
        runtimeStatus.setPhase("analyzing", "分析上下文中", "准备处理用户请求");
        System.out.print("\nAgent > ");
        try {
            while (!future.isDone()) {
                String frame = spinnerFrames[spinnerIndex++ % spinnerFrames.length];
                runtimeStatus.recentAction = "正在生成回复";
                runtimeStatus.refreshTasks(service.listSubagents("", 20));
                System.out.print("\rAgent > " + runtimeStatus.spinnerText() + " " + frame);
                System.out.flush();
                TimeUnit.MILLISECONDS.sleep(120);
            }
            String reply = future.get();
            runtimeStatus.setPhase("summarizing", "整理结果中", "生成完成，开始流式打印");
            persistSessionState(sessionStore, currentSessionId, summarizeTitle(userText));
            System.out.print("\rAgent > ");
            System.out.print(" ".repeat(Math.max(20, runtimeStatus.spinnerText().length() + 8)));
            System.out.print("\rAgent > ");
            streamText(reply == null ? "" : reply);
            runtimeStatus.setPhase("idle", "空闲", "回复完成");
            persistSessionState(sessionStore, currentSessionId, summarizeTitle(userText));
            System.out.println();
        } catch (Exception e) {
            runtimeStatus.markFailed(e.getMessage(), "streaming");
            persistSessionState(sessionStore, currentSessionId, summarizeTitle(userText));
            System.out.print("\rAgent > failed: " + e.getMessage());
            System.out.println();
        }
    }

    private void streamText(String text) throws InterruptedException {
        if (text == null || text.isBlank()) {
            System.out.print("我收到了你的消息，但暂时没有生成可用回复。");
            return;
        }
        for (String chunk : chunkText(text, 18)) {
            System.out.print(chunk);
            System.out.flush();
            TimeUnit.MILLISECONDS.sleep(18);
        }
    }

    private static String summarizeTitle(String text) {
        String normalized = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 60) {
            return normalized;
        }
        return normalized.substring(0, 57) + "...";
    }

    private static String shorten(String text, int maxLen) {
        String normalized = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen - 3)) + "...";
    }

    private static String statusMarker(String status) {
        return switch ((status == null ? "" : status).trim().toLowerCase()) {
            case "running" -> ">";
            case "pending" -> "~";
            case "completed" -> "+";
            case "failed", "timeout" -> "!";
            default -> "-";
        };
    }

    private static List<String> chunkText(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }
        int size = Math.max(1, chunkSize);
        for (int i = 0; i < text.length(); i += size) {
            chunks.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return chunks;
    }

    private void persistSessionState(SessionStateStore sessionStore, String sessionId, String title) {
        List<Map<String, Object>> jobs = service.listSubagents("", 20);
        runtimeStatus.refreshTasks(jobs);
        sessionStore.save(
                sessionId,
                title,
                workspaceRoot.toString(),
                runtimeStatus.phaseLabel,
                runtimeStatus.recentAction,
                runtimeStatus.lastError,
                runtimeStatus.tasks.running + runtimeStatus.tasks.pending);
    }

    private void printSessionRestorePreview(String sessionId) {
        List<SessionTranscriptStore.SessionMessage> history = service.history(sessionId);
        if (history.isEmpty()) {
            return;
        }
        int start = Math.max(0, history.size() - 4);
        System.out.println("Restored recent context:");
        for (int i = start; i < history.size(); i++) {
            SessionTranscriptStore.SessionMessage message = history.get(i);
            System.out.println("- [" + message.role() + "] " + shorten(message.content(), 120));
        }
    }
}
