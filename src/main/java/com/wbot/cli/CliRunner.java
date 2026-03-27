package com.wbot.cli;

import com.wbot.agent.CyberAgentService;
import com.wbot.config.AppConfig;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

public class CliRunner {
    private final AppConfig config;
    private final CyberAgentService service;

    public CliRunner(AppConfig config, Path workspaceRoot) {
        this.config = config;
        this.service = new CyberAgentService(config, workspaceRoot);
    }

    public void run() {
        System.out.println("CyberCore CLI | type quit/exit to leave");
        SessionStateStore sessionStore = new SessionStateStore(config.agent.sessionStateFilePath);
        String currentSessionId = sessionStore.load();
        if (currentSessionId == null || currentSessionId.isBlank()) {
            currentSessionId = config.agent.sessionId;
        }
        sessionStore.save(currentSessionId);
        System.out.println("Current session: " + currentSessionId + "  (/new for new session)");

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
                if ("/new".equalsIgnoreCase(userText)) {
                    currentSessionId = service.newSessionId();
                    sessionStore.save(currentSessionId);
                    System.out.println("Started new session: " + currentSessionId);
                    continue;
                }
                if ("quit".equalsIgnoreCase(userText) || "exit".equalsIgnoreCase(userText)) {
                    System.out.println("Session closed.");
                    return;
                }

                String reply = service.ask(currentSessionId, userText);
                System.out.println("\nAgent > " + reply);
            }
        } catch (Exception e) {
            throw new RuntimeException("CLI runtime failed", e);
        }
    }
}
