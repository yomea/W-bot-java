package com.wbot.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.wbot.util.Jsons;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SessionTranscriptStore {
    public record SessionMessage(String role, String content, String createdAt) {}

    private final Path rootDir;

    public SessionTranscriptStore(Path rootDir) {
        this.rootDir = rootDir.toAbsolutePath().normalize();
    }

    public synchronized void append(String sessionId, String role, String content) {
        try {
            List<SessionMessage> messages = new ArrayList<>(list(sessionId));
            messages.add(new SessionMessage(role, content, OffsetDateTime.now().toString()));
            write(sessionId, messages);
        } catch (Exception e) {
            throw new RuntimeException("Failed to append session transcript: " + sessionId, e);
        }
    }

    public synchronized List<SessionMessage> list(String sessionId) {
        try {
            Path file = filePath(sessionId);
            if (!Files.exists(file)) {
                return List.of();
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            List<Map<String, String>> payload = Jsons.MAPPER.readValue(
                    raw,
                    new TypeReference<List<Map<String, String>>>() {});
            List<SessionMessage> messages = new ArrayList<>();
            for (Map<String, String> item : payload) {
                messages.add(new SessionMessage(
                        item.getOrDefault("role", ""),
                        item.getOrDefault("content", ""),
                        item.getOrDefault("created_at", "")));
            }
            return messages;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void write(String sessionId, List<SessionMessage> messages) throws Exception {
        Path file = filePath(sessionId);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        List<Map<String, Object>> payload = new ArrayList<>();
        for (SessionMessage message : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.role());
            item.put("content", message.content());
            item.put("created_at", message.createdAt());
            payload.add(item);
        }
        Files.writeString(file, Jsons.toJson(payload) + "\n", StandardCharsets.UTF_8);
    }

    private Path filePath(String sessionId) {
        return rootDir.resolve(sanitizeSessionId(sessionId) + ".json");
    }

    private static String sanitizeSessionId(String sessionId) {
        return sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
