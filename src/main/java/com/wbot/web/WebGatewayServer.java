package com.wbot.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.wbot.agent.CyberAgentService;
import com.wbot.cli.SessionStateStore;
import com.wbot.config.AppConfig;
import com.wbot.util.Jsons;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class WebGatewayServer {
    private final AppConfig.WebConfig web;
    private final CyberAgentService service;
    private final SessionStateStore sessionStore;
    private final Path workspaceRoot;
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public WebGatewayServer(AppConfig config, Path workspaceRoot) {
        this.web = config.channels.web;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.service = new CyberAgentService(config, workspaceRoot);
        this.sessionStore = new SessionStateStore(config.agent.sessionStateFilePath);
    }

    public void startBlocking() {
        if (!web.enabled) {
            System.out.println("Web channel disabled by config.");
            return;
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(web.host, web.port), 0);
            server.createContext("/", new IndexHandler());
            server.createContext("/api/health", exchange -> writeJson(exchange, 200, Map.of("status", "ok")));
            server.createContext("/api/session/new", new NewSessionHandler());
            server.createContext("/api/sessions", new SessionsHandler());
            server.createContext("/api/history", new HistoryHandler());
            server.createContext("/api/chat", new ChatHandler());
            server.createContext("/api/chat/stream", new ChatStreamHandler());
            server.setExecutor(Executors.newFixedThreadPool(8));
            server.start();
            System.out.println("Web gateway listening on http://" + web.host + ":" + web.port);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start Web gateway", e);
        }
    }

    private final class IndexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("code", 405, "msg", "Method not allowed"));
                return;
            }
            try (InputStream in = WebGatewayServer.class.getClassLoader().getResourceAsStream("web/index.html")) {
                if (in == null) {
                    writeJson(exchange, 500, Map.of("code", 500, "msg", "Missing web/index.html"));
                    return;
                }
                byte[] bytes = in.readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }
        }
    }

    private final class NewSessionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("code", 405, "msg", "Method not allowed"));
                return;
            }
            String sessionId = service.newSessionId();
            sessionStore.save(sessionId, "", workspaceRoot.toString());
            writeJson(exchange, 200, Map.of("session_id", sessionId));
        }
    }

    private final class SessionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("code", 405, "msg", "Method not allowed"));
                return;
            }
            List<Map<String, String>> sessions = sessionStore.listRecent().stream()
                    .map(item -> {
                        Map<String, String> row = new LinkedHashMap<>();
                        row.put("session_id", item.sessionId());
                        row.put("updated_at", item.updatedAt());
                        row.put("title", item.title());
                        row.put("workspace_root", item.workspaceRoot());
                        return row;
                    })
                    .toList();
            writeJson(exchange, 200, Map.of("sessions", sessions));
        }
    }

    private final class HistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("code", 405, "msg", "Method not allowed"));
                return;
            }
            String query = exchange.getRequestURI().getRawQuery();
            Map<String, String> params = parseQuery(query);
            String sessionId = params.getOrDefault("session_id", "").trim();
            if (sessionId.isBlank()) {
                writeJson(exchange, 400, Map.of("code", 400, "msg", "session_id is required"));
                return;
            }
            List<Map<String, String>> messages = service.history(sessionId).stream()
                    .map(item -> Map.of(
                            "role", item.role(),
                            "content", item.content(),
                            "created_at", item.createdAt()))
                    .toList();
            writeJson(exchange, 200, Map.of("session_id", sessionId, "messages", messages));
        }
    }

    private final class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("code", 405, "msg", "Method not allowed"));
                return;
            }
            Map<String, Object> payload = readJsonBody(exchange);
            if (payload == null) {
                writeJson(exchange, 400, Map.of("code", 400, "msg", "invalid json"));
                return;
            }
            String message = String.valueOf(payload.getOrDefault("message", "")).trim();
            String sessionId = String.valueOf(payload.getOrDefault("session_id", "")).trim();
            if (message.isBlank()) {
                writeJson(exchange, 400, Map.of("code", 400, "msg", "message is required"));
                return;
            }
            if (sessionId.isBlank()) {
                sessionId = service.newSessionId();
            }
            String reply;
            synchronized (sessionLock(sessionId)) {
                reply = service.ask(sessionId, message);
            }
            sessionStore.save(sessionId, summarizeTitle(message), workspaceRoot.toString());
            writeJson(exchange, 200, Map.of("session_id", sessionId, "reply", reply));
        }
    }

    private final class ChatStreamHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("code", 405, "msg", "Method not allowed"));
                return;
            }
            Map<String, Object> payload = readJsonBody(exchange);
            if (payload == null) {
                writeJson(exchange, 400, Map.of("code", 400, "msg", "invalid json"));
                return;
            }
            String message = String.valueOf(payload.getOrDefault("message", "")).trim();
            String sessionId = String.valueOf(payload.getOrDefault("session_id", "")).trim();
            if (message.isBlank()) {
                writeJson(exchange, 400, Map.of("code", 400, "msg", "message is required"));
                return;
            }
            if (sessionId.isBlank()) {
                sessionId = service.newSessionId();
            }

            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.getResponseHeaders().add("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            OutputStream output = exchange.getResponseBody();
            try {
                writeSse(output, null, "stream-open");
                writeSse(output, "session", Jsons.toJson(Map.of("session_id", sessionId)));
                writeSse(output, "thinking", Jsons.toJson(Map.of("text", "W-bot 正在思考...")));
                writeSse(output, "status", Jsons.toJson(Map.of("text", "请求已接收，开始处理。")));

                String reply;
                synchronized (sessionLock(sessionId)) {
                    reply = service.ask(sessionId, message);
                }
                if (reply == null || reply.isBlank()) {
                    reply = "我收到了你的消息，但暂时没有生成可用回复。";
                }

                sessionStore.save(sessionId, summarizeTitle(message), workspaceRoot.toString());
                writeSse(output, "status", Jsons.toJson(Map.of("text", "正在整理回复...")));
                for (String chunk : chunkText(reply, 24)) {
                    writeSse(output, "token", Jsons.toJson(Map.of("text", chunk, "kind", "answer")));
                }
                writeSse(output, "done", Jsons.toJson(Map.of("reply", reply, "_streamed", true)));
            } catch (Exception e) {
                try {
                    writeSse(output, "error", Jsons.toJson(Map.of("message", "Chat processing failed")));
                } catch (Exception ignored) {
                }
            } finally {
                try {
                    output.close();
                } catch (Exception ignored) {
                }
                exchange.close();
            }
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            out.put(key, value);
        }
        return out;
    }

    private Object sessionLock(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, ignored -> new Object());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJsonBody(HttpExchange exchange) {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            return Jsons.MAPPER.readValue(body, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeJson(HttpExchange exchange, int status, Map<String, ?> payload) throws IOException {
        byte[] bytes = Jsons.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void writeSse(OutputStream output, String event, String data) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (event == null || event.isBlank()) {
            sb.append(": ").append(data).append("\n\n");
        } else {
            sb.append("event: ").append(event).append("\n");
            for (String line : data.split("\\R", -1)) {
                sb.append("data: ").append(line).append("\n");
            }
            sb.append("\n");
        }
        output.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static List<String> chunkText(String text, int size) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int step = Math.max(1, size);
        for (int i = 0; i < text.length(); i += step) {
            chunks.add(text.substring(i, Math.min(text.length(), i + step)));
        }
        return chunks;
    }

    private static String summarizeTitle(String text) {
        String normalized = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 60) {
            return normalized;
        }
        return normalized.substring(0, 57) + "...";
    }
}
