package com.wbot.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.wbot.util.Jsons;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class SessionStateStore {
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
            String sessionId = node.path("session_id").asText("");
            return sessionId.isBlank() ? null : sessionId;
        } catch (Exception e) {
            return null;
        }
    }

    public void save(String sessionId) {
        try {
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            Files.writeString(filePath, Jsons.toJson(Map.of("session_id", sessionId)) + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save session state: " + filePath, e);
        }
    }
}
