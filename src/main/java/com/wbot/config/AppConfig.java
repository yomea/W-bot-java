package com.wbot.config;

import java.util.ArrayList;
import java.util.List;

public class AppConfig {
    public AgentConfig agent = new AgentConfig();
    public Channels channels = new Channels();
    public String threadPrefix = "feishu";

    public static class AgentConfig {
        public String dashscopeApiKey = "";
        public String bailianBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        public String bailianModelName = "qwen-plus";
        public String e2bApiKey = "";
        public String tavilyApiKey = "";
        public String postgresDsn = "";
        public String memoryFilePath = "MEMORY.MD";
        public String userId = "cli_user";
        public String sessionId = "";
        public String sessionStateFilePath = ".cybercore_session.json";
        public int retrieveTopK = 4;
        public boolean enableStreaming = true;
        public boolean enableExecTool = false;
        public boolean enableCronService = false;
        public boolean enableSkills = true;
        public String skillsWorkspaceDir = "skills";
        public String skillsBuiltinDir = "";
        public List<McpServerConfig> mcpServers = new ArrayList<>();
    }

    public static class Channels {
        public FeishuConfig feishu = new FeishuConfig();
        public WebConfig web = new WebConfig();
    }

    public static class FeishuConfig {
        public boolean enabled = true;
        public String appId = "";
        public String appSecret = "";
        public String encryptKey = "";
        public String verificationToken = "";
        public List<String> allowFrom = List.of("*");
        public String reactEmoji = "THUMBSUP";
        public String groupPolicy = "mention";
        public boolean replyToMessage = true;
        public int port = 8090;
        public String path = "/feishu/events";
    }

    public static class WebConfig {
        public boolean enabled = true;
        public String host = "127.0.0.1";
        public int port = 8000;
    }

    public static class McpServerConfig {
        public String name = "server";
        public String baseUrl = "";
        public boolean enabled = true;
        public String discoveryPath = "/tools";
        public String invokePathTemplate = "/tools/{tool}";
    }
}
