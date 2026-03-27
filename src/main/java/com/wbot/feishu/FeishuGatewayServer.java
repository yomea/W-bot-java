package com.wbot.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.wbot.agent.CyberAgentService;
import com.wbot.config.AppConfig;
import com.wbot.util.Jsons;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

public class FeishuGatewayServer {
    private final AppConfig config;
    private final AppConfig.FeishuConfig feishu;
    private final CyberAgentService service;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final Map<String, Boolean> seenMessageIds = new LinkedHashMap<>();
    private String tenantAccessToken = "";
    private Instant tokenExpireAt = Instant.EPOCH;

    public FeishuGatewayServer(AppConfig config, Path workspaceRoot) {
        this.config = config;
        this.feishu = config.channels.feishu;
        this.service = new CyberAgentService(config, workspaceRoot);
    }

    public void startBlocking() {
        if (!feishu.enabled) {
            System.out.println("Feishu channel disabled by config.");
            return;
        }
        if (isBlank(feishu.appId) || isBlank(feishu.appSecret)) {
            throw new IllegalStateException("Feishu appId/appSecret is empty.");
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(feishu.port), 0);
            server.createContext(feishu.path, new EventHandler());
            server.setExecutor(Executors.newFixedThreadPool(8));
            server.start();
            System.out.println("Feishu gateway listening on http://0.0.0.0:" + feishu.port + feishu.path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start Feishu gateway", e);
        }
    }

    private class EventHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("code", 405, "msg", "Method not allowed"));
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root;
            try {
                root = Jsons.readTree(body);
            } catch (Exception e) {
                writeJson(exchange, 400, Map.of("code", 400, "msg", "invalid json"));
                return;
            }

            if ("url_verification".equalsIgnoreCase(root.path("type").asText())) {
                String challenge = root.path("challenge").asText("");
                writeJson(exchange, 200, Map.of("challenge", challenge));
                return;
            }

            try {
                handleEvent(root);
                writeJson(exchange, 200, Map.of("code", 0));
            } catch (Exception e) {
                writeJson(exchange, 500, Map.of("code", 500, "msg", e.getMessage()));
            }
        }
    }

    private void handleEvent(JsonNode root) {
        JsonNode event = root.path("event");
        if (event.isMissingNode() || event.isNull()) {
            event = root;
        }
        JsonNode message = event.path("message");
        JsonNode sender = event.path("sender");
        JsonNode senderId = sender.path("sender_id");

        String messageId = text(message, "message_id");
        String chatId = text(message, "chat_id");
        String chatType = text(message, "chat_type").toLowerCase();
        String messageType = safe(text(message, "message_type"), text(message, "msg_type")).toLowerCase();
        String senderType = text(sender, "sender_type").toLowerCase();
        String openId = text(senderId, "open_id");

        if (messageId.isBlank() || chatId.isBlank()) {
            return;
        }
        if (isDuplicate(messageId)) {
            return;
        }
        if ("bot".equals(senderType)) {
            return;
        }
        if (!isAllowed(openId)) {
            return;
        }

        String text = extractText(message.path("content").asText(""), messageType);
        if (text.isBlank()) {
            return;
        }
        if ("group".equals(chatType) && "mention".equalsIgnoreCase(feishu.groupPolicy)) {
            if (!isGroupMentioned(event, text)) {
                return;
            }
        }

        String sessionKey = "group".equals(chatType) ? chatId : (openId.isBlank() ? chatId : openId);
        String sessionId = config.threadPrefix + ":" + sessionKey;
        String reply = service.ask(sessionId, text);
        if (reply.isBlank()) {
            reply = "我收到了你的消息，但暂时没有生成可用回复。";
        }
        sendText(chatId, openId, messageId, reply);
    }

    private void sendText(String chatId, String openId, String replyMessageId, String text) {
        String content = Jsons.toJson(Map.of("text", text));
        if (feishu.replyToMessage && !replyMessageId.isBlank()) {
            String url = "https://open.feishu.cn/open-apis/im/v1/messages/" + replyMessageId + "/reply";
            Map<String, Object> body = Map.of("msg_type", "text", "content", content);
            String response = postJson(url, body, authHeaders());
            if (response != null && !response.isBlank()) {
                return;
            }
        }

        String receiveIdType = chatId.startsWith("oc_") ? "chat_id" : "open_id";
        String receiveId = "chat_id".equals(receiveIdType) ? chatId : (openId.isBlank() ? chatId : openId);
        URI uri = URI.create("https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=" + receiveIdType);
        Map<String, Object> body = Map.of("receive_id", receiveId, "msg_type", "text", "content", content);
        postJson(uri.toString(), body, authHeaders());
    }

    private Map<String, String> authHeaders() {
        return Map.of("Authorization", "Bearer " + getTenantAccessToken());
    }

    private synchronized String getTenantAccessToken() {
        Instant now = Instant.now();
        if (!tenantAccessToken.isBlank() && now.isBefore(tokenExpireAt.minusSeconds(60))) {
            return tenantAccessToken;
        }
        String url = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";
        Map<String, Object> body = Map.of("app_id", feishu.appId, "app_secret", feishu.appSecret);
        String response = postJson(url, body, Map.of());
        if (response == null || response.isBlank()) {
            throw new RuntimeException("Failed to get tenant_access_token");
        }
        JsonNode json = Jsons.readTree(response);
        if (json.path("code").asInt(-1) != 0) {
            throw new RuntimeException("tenant_access_token error: " + json.path("msg").asText(""));
        }
        tenantAccessToken = json.path("tenant_access_token").asText("");
        int expireSec = json.path("expire").asInt(7200);
        tokenExpireAt = Instant.now().plusSeconds(expireSec);
        return tenantAccessToken;
    }

    private String postJson(String url, Map<String, Object> payload, Map<String, String> headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(Jsons.toJson(payload), StandardCharsets.UTF_8));
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.body();
        } catch (Exception e) {
            return "";
        }
    }

    private void writeJson(HttpExchange exchange, int status, Map<String, Object> payload) throws IOException {
        byte[] bytes = Jsons.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private boolean isAllowed(String openId) {
        if (feishu.allowFrom == null || feishu.allowFrom.isEmpty()) {
            return false;
        }
        return feishu.allowFrom.contains("*") || feishu.allowFrom.contains(openId);
    }

    private boolean isDuplicate(String messageId) {
        synchronized (seenMessageIds) {
            if (seenMessageIds.containsKey(messageId)) {
                return true;
            }
            seenMessageIds.put(messageId, Boolean.TRUE);
            if (seenMessageIds.size() > 1000) {
                String first = seenMessageIds.keySet().iterator().next();
                seenMessageIds.remove(first);
            }
            return false;
        }
    }

    private static String extractText(String contentRaw, String messageType) {
        if (contentRaw == null || contentRaw.isBlank()) {
            return "";
        }
        try {
            JsonNode content = Jsons.readTree(contentRaw);
            if ("text".equalsIgnoreCase(messageType)) {
                return content.path("text").asText("");
            }
            if ("post".equalsIgnoreCase(messageType)) {
                JsonNode post = content.path("post");
                JsonNode locale = post.path("zh_cn").isMissingNode() ? post.path("en_us") : post.path("zh_cn");
                JsonNode lines = locale.path("content");
                if (!lines.isArray()) {
                    return "";
                }
                StringBuilder sb = new StringBuilder();
                for (JsonNode line : lines) {
                    if (!line.isArray()) {
                        continue;
                    }
                    for (JsonNode part : line) {
                        String txt = part.path("text").asText("");
                        if (!txt.isBlank()) {
                            if (sb.length() > 0) {
                                sb.append('\n');
                            }
                            sb.append(txt.trim());
                        }
                    }
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return contentRaw;
        }
        return contentRaw;
    }

    private static boolean isGroupMentioned(JsonNode event, String text) {
        JsonNode mentions = event.path("message").path("mentions");
        if (mentions.isArray() && !mentions.isEmpty()) {
            return true;
        }
        String lower = text.toLowerCase();
        return lower.contains("@_all") || lower.contains("<at ");
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.path(field).asText("");
    }

    private static String safe(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
