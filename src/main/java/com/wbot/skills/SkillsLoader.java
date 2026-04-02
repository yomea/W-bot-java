package com.wbot.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SkillsLoader {
    public record SkillSpec(
            String name,
            String description,
            Path path,
            String source,
            boolean always,
            List<String> requiresBins,
            List<String> requiresEnv,
            String content) {}

    public record SkillRequirementCheck(boolean available, List<String> missingBins, List<String> missingEnv) {}

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\R(.*?)\\R---\\R?", Pattern.DOTALL);

    private final Path workspaceSkillsDir;
    private final Path builtinSkillsDir;

    public SkillsLoader(String workspaceSkillsDir, String builtinSkillsDir) {
        this.workspaceSkillsDir = resolvePath(workspaceSkillsDir == null || workspaceSkillsDir.isBlank() ? "skills" : workspaceSkillsDir);
        this.builtinSkillsDir = resolvePath(builtinSkillsDir == null || builtinSkillsDir.isBlank()
                ? "src/main/resources/skills_catalog"
                : builtinSkillsDir);
    }

    public List<SkillSpec> listSkills(boolean filterUnavailable) {
        Map<String, SkillSpec> merged = new LinkedHashMap<>();
        for (SkillSpec item : scanDir(builtinSkillsDir, "builtin")) {
            merged.put(item.name(), item);
        }
        for (SkillSpec item : scanDir(workspaceSkillsDir, "workspace")) {
            merged.put(item.name(), item);
        }
        List<SkillSpec> skills = merged.values().stream()
                .sorted(Comparator.comparing(SkillSpec::name))
                .toList();
        if (!filterUnavailable) {
            return skills;
        }
        return skills.stream().filter(skill -> checkRequirements(skill).available()).toList();
    }

    public SkillSpec getSkill(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return listSkills(false).stream()
                .filter(skill -> skill.name().equals(name.trim()))
                .findFirst()
                .orElse(null);
    }

    public String loadSkill(String name) {
        SkillSpec skill = getSkill(name);
        return skill == null ? null : readText(skill.path());
    }

    public List<SkillSpec> getAlwaysSkills() {
        List<SkillSpec> out = new ArrayList<>();
        for (SkillSpec skill : listSkills(false)) {
            SkillRequirementCheck check = checkRequirements(skill);
            if (skill.always() && check.available()) {
                out.add(skill);
            }
        }
        return out;
    }

    public String loadSkillsForContext(List<String> skillNames) {
        List<String> parts = new ArrayList<>();
        for (String skillName : skillNames) {
            String content = loadSkill(skillName);
            if (content == null || content.isBlank()) {
                continue;
            }
            String stripped = stripFrontmatter(content);
            if (!stripped.isBlank()) {
                parts.add("### Skill: " + skillName + "\n\n" + stripped);
            }
        }
        return String.join("\n\n---\n\n", parts);
    }

    public String buildSkillsSummary() {
        List<SkillSpec> skills = listSkills(false);
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<skills>");
        for (SkillSpec skill : skills) {
            SkillRequirementCheck check = checkRequirements(skill);
            sb.append("\n  <skill available=\"").append(check.available() ? "true" : "false").append("\">")
                    .append("\n    <name>").append(escapeXml(skill.name())).append("</name>")
                    .append("\n    <description>").append(escapeXml(skill.description().isBlank() ? skill.name() : skill.description())).append("</description>")
                    .append("\n    <location>").append(escapeXml(skill.path().toString())).append("</location>");
            if (!check.available()) {
                List<String> missing = new ArrayList<>();
                for (String item : check.missingBins()) {
                    missing.add("CLI: " + item);
                }
                for (String item : check.missingEnv()) {
                    missing.add("ENV: " + item);
                }
                if (!missing.isEmpty()) {
                    sb.append("\n    <requires>").append(escapeXml(String.join(", ", missing))).append("</requires>");
                }
            }
            sb.append("\n  </skill>");
        }
        sb.append("\n</skills>");
        return sb.toString();
    }

    public SkillRequirementCheck checkRequirements(SkillSpec skill) {
        List<String> missingBins = new ArrayList<>();
        for (String bin : skill.requiresBins()) {
            if (!isCommandAvailable(bin)) {
                missingBins.add(bin);
            }
        }
        List<String> missingEnv = new ArrayList<>();
        for (String env : skill.requiresEnv()) {
            String value = System.getenv(env);
            if (value == null || value.isBlank()) {
                missingEnv.add(env);
            }
        }
        return new SkillRequirementCheck(missingBins.isEmpty() && missingEnv.isEmpty(), missingBins, missingEnv);
    }

    private List<SkillSpec> scanDir(Path skillsDir, String source) {
        if (!Files.isDirectory(skillsDir)) {
            return List.of();
        }
        try {
            List<SkillSpec> out = new ArrayList<>();
            try (var stream = Files.list(skillsDir)) {
                for (Path item : stream.sorted().toList()) {
                    Path skillFile = item.resolve("SKILL.md");
                    if (!Files.isDirectory(item) || !Files.isRegularFile(skillFile)) {
                        continue;
                    }
                    SkillSpec parsed = parseSkillFile(skillFile, item.getFileName().toString(), source);
                    if (parsed != null) {
                        out.add(parsed);
                    }
                }
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    private SkillSpec parseSkillFile(Path skillFile, String fallbackName, String source) {
        String raw = readText(skillFile);
        if (raw == null) {
            return null;
        }
        ParsedFrontmatter parsed = parseFrontmatter(raw);
        Map<String, String> meta = parsed.meta();
        String metadataRaw = meta.getOrDefault("metadata", "");
        Map<String, Object> metadata = parseMetadataJson(metadataRaw);
        Map<String, Object> requires = metadata.get("requires") instanceof Map<?, ?> map
                ? castMap(map)
                : Map.of();
        List<String> requiresBins = toStringList(requires.get("bins"));
        List<String> requiresEnv = toStringList(requires.get("env"));
        boolean always = parseBoolean(meta.get("always")) || parseBoolean(Objects.toString(metadata.get("always"), ""));
        String name = valueOrDefault(meta.get("name"), fallbackName);
        String description = valueOrDefault(meta.get("description"), "");
        return new SkillSpec(
                name,
                description,
                skillFile.toAbsolutePath().normalize(),
                source,
                always,
                requiresBins,
                requiresEnv,
                parsed.body().trim());
    }

    private static ParsedFrontmatter parseFrontmatter(String raw) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return new ParsedFrontmatter(Map.of(), raw);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        String payload = matcher.group(1);
        for (String line : payload.split("\\R")) {
            int idx = line.indexOf(':');
            if (idx < 0) {
                continue;
            }
            String key = line.substring(0, idx).trim();
            String value = line.substring(idx + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            meta.put(key, value);
        }
        return new ParsedFrontmatter(meta, raw.substring(matcher.end()));
    }

    private static Map<String, Object> parseMetadataJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = com.wbot.util.Jsons.MAPPER.readValue(raw, Map.class);
            if (parsed instanceof Map<?, ?> map) {
                return castMap(map);
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String text = Objects.toString(item, "").trim();
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return out;
    }

    private static boolean parseBoolean(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String stripFrontmatter(String content) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        return matcher.find() ? content.substring(matcher.end()).trim() : content.trim();
    }

    private static boolean isCommandAvailable(String command) {
        try {
            Process process = new ProcessBuilder("zsh", "-lc", "command -v " + escapeShell(command))
                    .redirectErrorStream(true)
                    .start();
            boolean done = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            return done && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String escapeShell(String text) {
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    private static Path resolvePath(String path) {
        Path candidate = Paths.get(path);
        return candidate.isAbsolute() ? candidate.normalize() : Path.of("").toAbsolutePath().resolve(candidate).normalize();
    }

    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String escapeXml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private record ParsedFrontmatter(Map<String, String> meta, String body) {}
}
