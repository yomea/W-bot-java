package com.wbot;

import com.wbot.cli.CliRunner;
import com.wbot.config.AppConfig;
import com.wbot.config.ConfigLoader;
import com.wbot.feishu.FeishuGatewayServer;
import com.wbot.web.WebGatewayServer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class Main {
    public static void main(String[] args) {
        Map<String, String> argMap = parseArgs(args);
        String mode = argMap.getOrDefault("mode", "cli");
        String configPath = argMap.getOrDefault("config", ConfigLoader.DEFAULT_LOCATION);
        AppConfig config = ConfigLoader.load(configPath);
        Path cwd = Path.of("").toAbsolutePath();

        if ("feishu".equalsIgnoreCase(mode)) {
            FeishuGatewayServer server = new FeishuGatewayServer(config, cwd);
            server.startBlocking();
            return;
        }
        if ("web".equalsIgnoreCase(mode)) {
            WebGatewayServer server = new WebGatewayServer(config, cwd);
            server.startBlocking();
            return;
        }

        CliRunner cliRunner = new CliRunner(config, cwd);
        cliRunner.run();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new HashMap<>();
        if (args.length > 0 && !args[0].startsWith("--")) {
            out.put("mode", args[0]);
        }
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                out.put("config", args[i + 1]);
            }
        }
        return out;
    }
}
