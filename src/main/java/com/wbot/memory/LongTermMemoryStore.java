package com.wbot.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LongTermMemoryStore {
    private static final String HEADER = "# Long-term Memory";
    private static final List<String> SECTIONS = List.of(
            "User Information", "Preferences", "Project Context", "Important Notes");
    private static final Map<String, String> PLACEHOLDERS = Map.of(
            "User Information", "(Important facts about the user)",
            "Preferences", "(User preferences learned over time)",
            "Project Context", "(Information about ongoing projects)",
            "Important Notes", "(Things to remember)"
    );

    private final Path path;

    public LongTermMemoryStore(String memoryFilePath) {
        this.path = Path.of(memoryFilePath).toAbsolutePath();
        ensureFileExists();
    }

    public synchronized List<String> retrieve(String query, int k) {
        Map<String, List<String>> sections = readSections();
        if (query == null || query.isBlank()) {
            return retrieveRecent(k);
        }
        List<Scored> scores = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
            for (String item : entry.getValue()) {
                int s = score(item, query);
                if (s > 0) {
                    scores.add(new Scored(s, item));
                }
            }
        }
        return scores.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(Math.max(1, k))
                .map(Scored::text)
                .toList();
    }

    public synchronized List<String> retrieveRecent(int k) {
        Map<String, List<String>> sections = readSections();
        List<String> all = new ArrayList<>();
        for (String section : SECTIONS) {
            all.addAll(sections.getOrDefault(section, List.of()));
        }
        if (all.size() <= k) {
            return all;
        }
        return all.subList(all.size() - k, all.size());
    }

    public synchronized String save(String text, String memoryType) {
        String clean = normalizeSpace(text);
        if (clean.isBlank()) {
            return "";
        }
        Map<String, List<String>> sections = readSections();
        String section = mapSection(memoryType);
        List<String> entries = sections.computeIfAbsent(section, ignored -> new ArrayList<>());
        if (entries.stream().map(LongTermMemoryStore::normalizeText).anyMatch(normalizeText(clean)::equals)) {
            return Integer.toHexString(clean.hashCode());
        }
        entries.add(OffsetDateTime.now() + " [" + safe(memoryType, "experience") + "] " + clean);
        if (entries.size() > 80) {
            entries.subList(0, entries.size() - 80).clear();
        }
        writeSections(sections);
        return Integer.toHexString((section + ":" + clean).hashCode());
    }

    private void ensureFileExists() {
        try {
            if (Files.exists(path)) {
                return;
            }
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            writeSections(new LinkedHashMap<>());
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize MEMORY.MD: " + path, e);
        }
    }

    private Map<String, List<String>> readSections() {
        ensureFileExists();
        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (String s : SECTIONS) {
            sections.put(s, new ArrayList<>());
        }
        try {
            String content = Files.readString(path);
            String current = null;
            for (String line : content.split("\\R")) {
                if (line.startsWith("## ")) {
                    String sec = line.substring(3).trim();
                    current = sections.containsKey(sec) ? sec : null;
                    continue;
                }
                if (current != null && line.startsWith("- ")) {
                    String item = line.substring(2).trim();
                    if (!item.isBlank()) {
                        sections.get(current).add(item);
                    }
                }
            }
            return sections;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read memory file: " + path, e);
        }
    }

    private void writeSections(Map<String, List<String>> sections) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append("\n\n");
        sb.append("This file stores important information that should persist across sessions.").append("\n\n");
        for (String section : SECTIONS) {
            sb.append("## ").append(section).append("\n\n");
            List<String> items = sections.getOrDefault(section, List.of());
            if (items.isEmpty()) {
                sb.append(PLACEHOLDERS.get(section)).append("\n\n");
            } else {
                for (String item : items) {
                    sb.append("- ").append(item).append("\n");
                }
                sb.append("\n");
            }
        }
        sb.append("---\n\n");
        sb.append("*This file is automatically updated by w-bot-java when important information should be remembered.*\n");
        try {
            Files.writeString(path, sb.toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write memory file: " + path, e);
        }
    }

    private static int score(String text, String query) {
        String t = text.toLowerCase(Locale.ROOT);
        String q = query.toLowerCase(Locale.ROOT);
        if (t.contains(q)) {
            return Math.max(3, q.length());
        }
        int score = 0;
        Matcher matcher = Pattern.compile("[\\u4e00-\\u9fff]+|[a-zA-Z0-9_]+").matcher(q);
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (token.length() >= 2 && t.contains(token)) {
                score += token.length();
            }
        }
        return score;
    }

    private static String mapSection(String type) {
        String k = safe(type, "").trim().toLowerCase(Locale.ROOT);
        if (k.equals("preference") || k.equals("preferences")) {
            return "Preferences";
        }
        if (k.equals("user") || k.equals("profile") || k.equals("user_info")) {
            return "User Information";
        }
        if (k.equals("project") || k.equals("context") || k.equals("task")) {
            return "Project Context";
        }
        return "Important Notes";
    }

    private static String normalizeText(String text) {
        return normalizeSpace(text).toLowerCase(Locale.ROOT);
    }

    private static String normalizeSpace(String text) {
        return safe(text, "").trim().replaceAll("\\s+", " ");
    }

    private static String safe(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private record Scored(int score, String text) {}
}
