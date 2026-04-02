package com.wbot.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.wbot.util.Jsons;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SessionStateStore {
    public record SessionRecord(
            String sessionId,
            String updatedAt,
            String title,
            String workspaceRoot,
            String lastPhase,
            String lastAction,
            String lastError,
            int taskCount) {}

    private static final int RECENT_LIMIT = 20;
    private final Path filePath;

    public SessionStateStore(String filePath) {
        this.filePath = Path.of(filePath).toAbsolutePath();
    }

    public String load() {
        try {
            if (!Files.exists(filePath)) {
                return null;
            }
            JsonNode node = Jsons.readTree(Files.readString(filePath, StandardCharsets.UTF_8));
            String sessionId = node.path("current_session_id").asText("");
            if (sessionId.isBlank()) {
                sessionId = node.path("session_id").asText("");
            }
            return sessionId.isBlank() ? null : sessionId;
        } catch (Exception e) {
            return null;
        }
    }

    public void save(String sessionId) {
        save(sessionId, "", "", "", "", "", 0);
    }

    public void save(String sessionId, String title, String workspaceRoot) {
        save(sessionId, title, workspaceRoot, "", "", "", 0);
    }

    public void save(
            String sessionId,
            String title,
            String workspaceRoot,
            String lastPhase,
            String lastAction,
            String lastError,
            int taskCount) {
        try {
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            List<SessionRecord> recent = new ArrayList<>(listRecent());
            recent.removeIf(record -> record.sessionId().equals(sessionId));
            recent.add(0, new SessionRecord(
                    sessionId,
                    LocalDateTime.now().toString(),
                    title == null ? "" : title,
                    workspaceRoot == null ? "" : workspaceRoot,
                    lastPhase == null ? "" : lastPhase,
                    lastAction == null ? "" : lastAction,
                    lastError == null ? "" : lastError,
                    Math.max(0, taskCount)));
            if (recent.size() > RECENT_LIMIT) {
                recent = recent.subList(0, RECENT_LIMIT);
            }
            List<Map<String, Object>> recentPayload = recent.stream()
                    .map(record -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("session_id", record.sessionId());
                        item.put("updated_at", record.updatedAt());
                        item.put("title", record.title());
                        item.put("workspace_root", record.workspaceRoot());
                        item.put("last_phase", record.lastPhase());
                        item.put("last_action", record.lastAction());
                        item.put("last_error", record.lastError());
                        item.put("task_count", record.taskCount());
                        return item;
                    })
                    .collect(Collectors.toList());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("current_session_id", sessionId);
            payload.put("recent_sessions", recentPayload);
            Files.writeString(filePath, Jsons.toJson(payload) + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save session state: " + filePath, e);
        }
    }

    public List<SessionRecord> listRecent() {
        try {
            if (!Files.exists(filePath)) {
                return List.of();
            }
            JsonNode node = Jsons.readTree(Files.readString(filePath, StandardCharsets.UTF_8));
            List<SessionRecord> records = new ArrayList<>();
            JsonNode recent = node.path("recent_sessions");
            if (recent.isArray()) {
                for (JsonNode item : recent) {
                    String sessionId = item.path("session_id").asText("");
                    if (sessionId.isBlank()) {
                        continue;
                    }
                    records.add(new SessionRecord(
                            sessionId,
                            item.path("updated_at").asText(""),
                            item.path("title").asText(""),
                            item.path("workspace_root").asText(""),
                            item.path("last_phase").asText(""),
                            item.path("last_action").asText(""),
                            item.path("last_error").asText(""),
                            item.path("task_count").asInt(0)));
                }
            }
            String current = load();
            if (current != null && records.stream().noneMatch(record -> record.sessionId().equals(current))) {
                records.add(0, new SessionRecord(current, LocalDateTime.now().toString(), "", "", "", "", "", 0));
            }
            return records.stream()
                    .sorted(Comparator.comparing(SessionRecord::updatedAt).reversed())
                    .limit(RECENT_LIMIT)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }
}
