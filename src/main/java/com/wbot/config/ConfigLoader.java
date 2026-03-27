package com.wbot.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ConfigLoader {
    public static final String DEFAULT_LOCATION = "classpath:application.yml";

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);

    private ConfigLoader() {}

    public static AppConfig load(String configLocation) {
        String location = (configLocation == null || configLocation.isBlank())
                ? DEFAULT_LOCATION
                : configLocation;
        try {
            AppConfig cfg;
            if (location.startsWith("classpath:")) {
                String resourcePath = location.substring("classpath:".length());
                if (resourcePath.startsWith("/")) {
                    resourcePath = resourcePath.substring(1);
                }
                try (InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        throw new IllegalStateException("Config resource not found: " + location);
                    }
                    cfg = YAML_MAPPER.readValue(in, AppConfig.class);
                }
            } else {
                Path path = Path.of(location).toAbsolutePath();
                if (!Files.exists(path)) {
                    throw new IllegalStateException("Config file not found: " + path);
                }
                cfg = YAML_MAPPER.readValue(Files.readString(path), AppConfig.class);
            }
            if (cfg.agent.sessionId == null || cfg.agent.sessionId.isBlank()) {
                cfg.agent.sessionId = "cli_session_"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            }
            if (cfg.channels == null) {
                cfg.channels = new AppConfig.Channels();
            }
            if (cfg.channels.feishu == null) {
                cfg.channels.feishu = new AppConfig.FeishuConfig();
            }
            if (cfg.threadPrefix == null || cfg.threadPrefix.isBlank()) {
                cfg.threadPrefix = "feishu";
            }
            return cfg;
        } catch (Exception e) {
            throw new RuntimeException("Invalid config yaml: " + location, e);
        }
    }

    public static AppConfig defaultConfig() {
        AppConfig cfg = new AppConfig();
        cfg.agent.sessionId = "";
        cfg.agent.userId = "feishu_bot";
        return cfg;
    }

    private static String toPrettyJson(Object obj) {
        try {
            return YAML_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj) + "\n";
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
