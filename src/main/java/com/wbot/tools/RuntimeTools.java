package com.wbot.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.wbot.config.AppConfig;
import com.wbot.memory.LongTermMemoryStore;
import com.wbot.skills.SkillsLoader;
import com.wbot.subagent.SubagentManager;
import com.wbot.util.Jsons;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class RuntimeTools {
    private RuntimeTools() {}

    public static Toolkit buildToolkit(
            AppConfig.AgentConfig config,
            LongTermMemoryStore memoryStore,
            String userId,
            Path workspaceRoot,
            SubagentManager subagentManager,
            boolean allowSubagentTools) {
        Toolkit toolkit = new Toolkit();
        SkillsLoader skillsLoader = config.enableSkills
                ? new SkillsLoader(config.skillsWorkspaceDir, config.skillsBuiltinDir)
                : null;
        toolkit.registerTool(new BuiltinTools(config, memoryStore, userId, workspaceRoot, skillsLoader, subagentManager, allowSubagentTools));
        return toolkit;
    }

    public static final class BuiltinTools {
        private final AppConfig.AgentConfig config;
        private final LongTermMemoryStore memoryStore;
        private final String userId;
        private final Path workspaceRoot;
        private final SkillsLoader skillsLoader;
        private final SubagentManager subagentManager;
        private final boolean allowSubagentTools;
        private final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        public BuiltinTools(
                AppConfig.AgentConfig config,
                LongTermMemoryStore memoryStore,
                String userId,
                Path workspaceRoot,
                SkillsLoader skillsLoader,
                SubagentManager subagentManager,
                boolean allowSubagentTools) {
            this.config = config;
            this.memoryStore = memoryStore;
            this.userId = userId;
            this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
            this.skillsLoader = skillsLoader;
            this.subagentManager = subagentManager;
            this.allowSubagentTools = allowSubagentTools;
        }

        @Tool(name = "read_file", description = "Read a text file from workspace by line range.")
        public String readFile(
                @ToolParam(name = "path", description = "File path relative to workspace root") String path,
                @ToolParam(name = "start_line", description = "Start line, default 1") Integer startLine,
                @ToolParam(name = "end_line", description = "End line, default 300") Integer endLine) {
            try {
                Path target = resolvePath(path);
                if (!Files.exists(target) || !Files.isRegularFile(target)) {
                    return "File not found: " + target;
                }
                List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);
                int start = Math.max(1, startLine == null ? 1 : startLine);
                int end = Math.max(start, Math.min(lines.size(), endLine == null ? 300 : endLine));
                return String.join("\n", lines.subList(start - 1, end));
            } catch (Exception e) {
                return "read_file failed: " + e.getMessage();
            }
        }

        @Tool(name = "write_file", description = "Write text file in workspace. Creates parent directories automatically.")
        public String writeFile(
                @ToolParam(name = "path", description = "File path relative to workspace root") String path,
                @ToolParam(name = "content", description = "File content") String content,
                @ToolParam(name = "overwrite", description = "Whether to overwrite existing file") Boolean overwrite) {
            try {
                Path target = resolvePath(path);
                if (Files.exists(target) && Boolean.FALSE.equals(overwrite)) {
                    return "File already exists and overwrite=false: " + target;
                }
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
                return "Wrote " + (content == null ? 0 : content.length()) + " chars to " + target;
            } catch (Exception e) {
                return "write_file failed: " + e.getMessage();
            }
        }

        @Tool(name = "edit_file", description = "Edit file by replacing text.")
        public String editFile(
                @ToolParam(name = "path", description = "File path relative to workspace root") String path,
                @ToolParam(name = "find_text", description = "Text to find") String findText,
                @ToolParam(name = "replace_text", description = "Replacement text") String replaceText,
                @ToolParam(name = "replace_all", description = "Replace all matches") Boolean replaceAll) {
            try {
                Path target = resolvePath(path);
                if (!Files.exists(target) || !Files.isRegularFile(target)) {
                    return "File not found: " + target;
                }
                String source = Files.readString(target, StandardCharsets.UTF_8);
                if (!source.contains(findText)) {
                    return "No match found";
                }
                String updated;
                int changed;
                if (Boolean.TRUE.equals(replaceAll)) {
                    updated = source.replace(findText, replaceText);
                    changed = source.split(java.util.regex.Pattern.quote(findText), -1).length - 1;
                } else {
                    updated = source.replaceFirst(java.util.regex.Pattern.quote(findText), java.util.regex.Matcher.quoteReplacement(replaceText));
                    changed = 1;
                }
                Files.writeString(target, updated, StandardCharsets.UTF_8);
                return "Updated " + changed + " occurrence(s) in " + target;
            } catch (Exception e) {
                return "edit_file failed: " + e.getMessage();
            }
        }

        @Tool(name = "list_dir", description = "List files/directories under workspace path.")
        public String listDir(
                @ToolParam(name = "path", description = "Directory path in workspace") String path,
                @ToolParam(name = "recursive", description = "List recursively") Boolean recursive) {
            try {
                Path target = resolvePath(path == null || path.isBlank() ? "." : path);
                if (!Files.exists(target)) {
                    return "Path not found: " + target;
                }
                if (Files.isRegularFile(target)) {
                    return "FILE\t" + target + "\t" + Files.size(target);
                }
                int limit = 2000;
                java.util.stream.Stream<Path> stream = Boolean.TRUE.equals(recursive) ? Files.walk(target) : Files.list(target);
                return stream
                        .sorted(Comparator.comparing(Path::toString))
                        .limit(limit)
                        .map(p -> {
                            try {
                                String kind = Files.isDirectory(p) ? "DIR" : "FILE";
                                long size = Files.isRegularFile(p) ? Files.size(p) : 0L;
                                return kind + "\t" + p + "\t" + size;
                            } catch (IOException e) {
                                return "ERR\t" + p + "\t0";
                            }
                        })
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("(empty)");
            } catch (Exception e) {
                return "list_dir failed: " + e.getMessage();
            }
        }

        @Tool(name = "web_fetch", description = "Fetch a URL and return cleaned text excerpt.")
        public String webFetch(
                @ToolParam(name = "url", description = "Target url") String url,
                @ToolParam(name = "max_chars", description = "Maximum chars to return") Integer maxChars) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "WBotJava/1.0")
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                String cleaned = stripHtml(response.body());
                int max = Math.max(200, maxChars == null ? 8000 : maxChars);
                return cleaned.substring(0, Math.min(cleaned.length(), max));
            } catch (Exception e) {
                return "Fetch failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }

        @Tool(name = "web_search", description = "Search web via Tavily.")
        public String webSearch(
                @ToolParam(name = "query", description = "Search query") String query,
                @ToolParam(name = "max_results", description = "Maximum result size") Integer maxResults) {
            if (config.tavilyApiKey == null || config.tavilyApiKey.isBlank()) {
                return "TAVILY_API_KEY is not configured.";
            }
            int max = Math.max(1, maxResults == null ? 5 : maxResults);
            Map<String, Object> body = Map.of(
                    "api_key", config.tavilyApiKey,
                    "query", query,
                    "max_results", max);
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.tavily.com/search"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(Jsons.toJson(body), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                JsonNode node = Jsons.readTree(response.body());
                JsonNode results = node.get("results");
                if (results == null || !results.isArray() || results.isEmpty()) {
                    return "No results";
                }
                StringBuilder sb = new StringBuilder();
                int count = 0;
                for (JsonNode item : results) {
                    if (count >= max) {
                        break;
                    }
                    String title = item.path("title").asText("(no title)");
                    String link = item.path("url").asText("");
                    String content = item.path("content").asText("");
                    if (content.length() > 220) {
                        content = content.substring(0, 220) + "...";
                    }
                    sb.append("- ").append(title).append("\n  ").append(link).append("\n  ").append(content).append("\n");
                    count++;
                }
                return sb.toString().trim();
            } catch (Exception e) {
                return "web_search failed: " + e.getMessage();
            }
        }

        @Tool(name = "message", description = "Send an internal message to queue.")
        public String message(
                @ToolParam(name = "recipient", description = "Recipient") String recipient,
                @ToolParam(name = "content", description = "Message content") String content) {
            Map<String, Object> msg = Map.of(
                    "id", UUID.randomUUID().toString().replace("-", ""),
                    "recipient", recipient,
                    "content", content,
                    "created_at", OffsetDateTime.now().toString());
            try {
                Path file = workspaceRoot.resolve(".cybercore_messages.jsonl");
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, Jsons.toJson(msg) + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                return "Message queued: id=" + msg.get("id") + " recipient=" + recipient;
            } catch (Exception e) {
                return "message failed: " + e.getMessage();
            }
        }

        @Tool(name = "spawn", description = "Spawn a background subagent for an independent task.")
        public String spawn(
                @ToolParam(name = "task", description = "Task details") String task,
                @ToolParam(name = "label", description = "Optional short label") String label,
                @ToolParam(name = "agent_type", description = "worker/explore/plan/verify") String agentType) {
            if (!allowSubagentTools || subagentManager == null) {
                return "spawn disabled in this runtime";
            }
            var job = subagentManager.spawn(task, label, agentType, "parent");
            return "Spawned subagent: id=" + job.id()
                    + " status=" + job.status()
                    + " type=" + job.agentType()
                    + " label=" + (job.label().isBlank() ? "-" : job.label());
        }

        @Tool(name = "list_subagents", description = "List spawned subagents and their latest status.")
        public String listSubagents(
                @ToolParam(name = "status", description = "Optional status filter") String status,
                @ToolParam(name = "limit", description = "Maximum result count") Integer limit) {
            if (!allowSubagentTools || subagentManager == null) {
                return "list_subagents disabled in this runtime";
            }
            return Jsons.toJson(subagentManager.list(status, limit == null ? 20 : limit));
        }

        @Tool(name = "wait_subagent", description = "Wait for a spawned subagent to finish and return its result.")
        public String waitSubagent(
                @ToolParam(name = "id", description = "Subagent id") String id,
                @ToolParam(name = "timeout_seconds", description = "Timeout seconds") Integer timeoutSeconds) {
            if (!allowSubagentTools || subagentManager == null) {
                return "wait_subagent disabled in this runtime";
            }
            return Jsons.toJson(subagentManager.waitFor(id, timeoutSeconds == null ? 60 : timeoutSeconds));
        }

        @Tool(name = "save_memory", description = "Persist long-term memory.")
        public String saveMemory(
                @ToolParam(name = "text", description = "Memory text") String text,
                @ToolParam(name = "memory_type", description = "Memory type") String memoryType) {
            String id = memoryStore.save(text, memoryType == null ? "experience" : memoryType);
            if (id == null || id.isBlank()) {
                return "Memory backend unavailable";
            }
            return "Memory saved, id=" + id;
        }

        @Tool(name = "list_skills", description = "List available builtin/workspace skills and requirement status.")
        public String listSkills() {
            if (skillsLoader == null) {
                return "skills disabled by config.enableSkills=false";
            }
            List<Map<String, Object>> payload = new java.util.ArrayList<>();
            for (SkillsLoader.SkillSpec skill : skillsLoader.listSkills(false)) {
                SkillsLoader.SkillRequirementCheck check = skillsLoader.checkRequirements(skill);
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("name", skill.name());
                item.put("description", skill.description());
                item.put("source", skill.source());
                item.put("always", skill.always());
                item.put("path", skill.path().toString());
                item.put("available", check.available());
                item.put("missing_bins", check.missingBins());
                item.put("missing_env", check.missingEnv());
                payload.add(item);
            }
            return Jsons.toJson(payload);
        }

        @Tool(name = "read_skill", description = "Read a skill document by exact name.")
        public String readSkill(
                @ToolParam(name = "skill_name", description = "Exact skill name") String skillName) {
            if (skillsLoader == null) {
                return "skills disabled by config.enableSkills=false";
            }
            SkillsLoader.SkillSpec skill = skillsLoader.getSkill(skillName);
            if (skill == null) {
                return "Skill not found: " + skillName;
            }
            SkillsLoader.SkillRequirementCheck check = skillsLoader.checkRequirements(skill);
            StringBuilder sb = new StringBuilder();
            sb.append("Skill: ").append(skill.name())
                    .append("\nSource: ").append(skill.source())
                    .append("\nPath: ").append(skill.path())
                    .append("\nAvailable: ").append(check.available());
            if (!check.missingBins().isEmpty()) {
                sb.append("\nMissing CLI: ").append(String.join(", ", check.missingBins()));
            }
            if (!check.missingEnv().isEmpty()) {
                sb.append("\nMissing ENV: ").append(String.join(", ", check.missingEnv()));
            }
            sb.append("\n\n").append(skill.content());
            return sb.toString();
        }

        @Tool(name = "run_skill", description = "Load a skill and produce an execution brief for the current task. Prefer this when a user explicitly asks to use a skill.")
        public String runSkill(
                @ToolParam(name = "skill_name", description = "Exact skill name") String skillName,
                @ToolParam(name = "task", description = "Concrete task to perform with this skill") String task,
                @ToolParam(name = "arguments_json", description = "Optional JSON object string for structured arguments") String argumentsJson) {
            if (skillsLoader == null) {
                return "skills disabled by config.enableSkills=false";
            }
            SkillsLoader.SkillSpec skill = skillsLoader.getSkill(skillName);
            if (skill == null) {
                return "Skill not found: " + skillName;
            }
            SkillsLoader.SkillRequirementCheck check = skillsLoader.checkRequirements(skill);
            if (!check.available()) {
                return "Skill dependencies missing. CLI="
                        + String.join(", ", check.missingBins())
                        + " ENV=" + String.join(", ", check.missingEnv());
            }
            String argsBlock = "";
            if (argumentsJson != null && !argumentsJson.isBlank()) {
                try {
                    JsonNode args = Jsons.readTree(argumentsJson);
                    argsBlock = "\nArguments:\n" + Jsons.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(args);
                } catch (Exception e) {
                    argsBlock = "\nArguments(raw):\n" + argumentsJson;
                }
            }
            return "Follow this skill for the current task.\n"
                    + "Skill: " + skill.name() + "\n"
                    + "Description: " + (skill.description().isBlank() ? "(none)" : skill.description()) + "\n"
                    + "Task:\n" + task + argsBlock + "\n\n"
                    + "Skill instructions:\n" + skill.content();
        }

        @Tool(name = "exec", description = "Execute shell command in workspace.")
        public String exec(
                @ToolParam(name = "command", description = "Shell command") String command,
                @ToolParam(name = "timeout_sec", description = "Timeout in seconds") Integer timeoutSec) {
            if (!config.enableExecTool) {
                return "exec tool disabled by config.enableExecTool=false";
            }
            try {
                Process process = new ProcessBuilder("zsh", "-lc", command)
                        .directory(workspaceRoot.toFile())
                        .start();
                long timeout = Math.max(1, timeoutSec == null ? 30 : timeoutSec);
                boolean done = process.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS);
                if (!done) {
                    process.destroyForcibly();
                    return "Command timed out";
                }
                String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                return "exit_code=" + process.exitValue() + "\n\nstdout:\n" + stdout + "\n\nstderr:\n" + stderr;
            } catch (Exception e) {
                return "exec failed: " + e.getMessage();
            }
        }

        @Tool(name = "cron", description = "Manage simple cron task records.")
        public String cron(
                @ToolParam(name = "action", description = "list/create/delete") String action,
                @ToolParam(name = "task_name", description = "Task name") String taskName,
                @ToolParam(name = "schedule", description = "Cron schedule") String schedule,
                @ToolParam(name = "payload", description = "Payload") String payload) {
            if (!config.enableCronService) {
                return "cron tool disabled by config.enableCronService=false";
            }
            try {
                Path file = workspaceRoot.resolve(".cybercore_cron_jobs.json");
                List<Map<String, Object>> jobs = Files.exists(file)
                        ? Jsons.MAPPER.readValue(
                                Files.readString(file, StandardCharsets.UTF_8),
                                new TypeReference<List<Map<String, Object>>>() {})
                        : new java.util.ArrayList<>();
                if ("list".equalsIgnoreCase(action)) {
                    return Jsons.toJson(jobs);
                }
                if ("create".equalsIgnoreCase(action)) {
                    if (schedule == null || schedule.isBlank()) {
                        return "schedule is required for create";
                    }
                    Map<String, Object> job = Map.of(
                            "id", UUID.randomUUID().toString().replace("-", ""),
                            "task_name", taskName,
                            "schedule", schedule,
                            "payload", payload == null ? "" : payload,
                            "enabled", true,
                            "created_at", OffsetDateTime.now().toString());
                    jobs.add(job);
                    Files.writeString(file, Jsons.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(jobs), StandardCharsets.UTF_8);
                    return "Cron job created: id=" + job.get("id");
                }
                if ("delete".equalsIgnoreCase(action)) {
                    int before = jobs.size();
                    jobs = jobs.stream().filter(j -> !safeEquals(taskName, String.valueOf(j.get("task_name")))).toList();
                    Files.writeString(file, Jsons.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(jobs), StandardCharsets.UTF_8);
                    return "Cron jobs removed: " + (before - jobs.size());
                }
                return "Unsupported action. Use list/create/delete.";
            } catch (Exception e) {
                return "cron failed: " + e.getMessage();
            }
        }

        @Tool(name = "mcp_call", description = "Call configured pseudo-MCP HTTP tool endpoint.")
        public String mcpCall(
                @ToolParam(name = "server", description = "Server name") String server,
                @ToolParam(name = "tool", description = "Remote tool name") String tool,
                @ToolParam(name = "arguments_json", description = "JSON object string for arguments") String argumentsJson) {
            try {
                AppConfig.McpServerConfig mcp = config.mcpServers.stream()
                        .filter(s -> s.enabled && s.name != null && s.name.equals(server))
                        .findFirst()
                        .orElse(null);
                if (mcp == null || mcp.baseUrl == null || mcp.baseUrl.isBlank()) {
                    return "MCP server not found or disabled: " + server;
                }
                JsonNode args = Jsons.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
                String path = (mcp.invokePathTemplate == null ? "/tools/{tool}" : mcp.invokePathTemplate).replace("{tool}", tool);
                String url = trimSlash(mcp.baseUrl) + path;
                Map<String, Object> body = Map.of("arguments", Jsons.MAPPER.convertValue(args, Map.class));
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(Jsons.toJson(body), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return response.body();
            } catch (Exception e) {
                return "mcp_call failed: " + e.getMessage();
            }
        }

        private Path resolvePath(String path) {
            Path candidate = Path.of(path);
            Path resolved = candidate.isAbsolute() ? candidate.normalize() : workspaceRoot.resolve(candidate).normalize();
            if (!resolved.startsWith(workspaceRoot)) {
                throw new IllegalArgumentException("Path escapes workspace: " + path);
            }
            return resolved;
        }

        private static String stripHtml(String raw) {
            String noScript = raw.replaceAll("(?is)<script[\\s\\S]*?</script>", " ");
            String noStyle = noScript.replaceAll("(?is)<style[\\s\\S]*?</style>", " ");
            String text = noStyle.replaceAll("(?is)<[^>]+>", " ");
            return text.replaceAll("\\s+", " ").trim();
        }

        private static String trimSlash(String value) {
            String v = value == null ? "" : value.trim();
            while (v.endsWith("/")) {
                v = v.substring(0, v.length() - 1);
            }
            return v;
        }

        private static boolean safeEquals(String a, String b) {
            return (a == null ? "" : a).toLowerCase(Locale.ROOT)
                    .equals((b == null ? "" : b).toLowerCase(Locale.ROOT));
        }
    }
}
