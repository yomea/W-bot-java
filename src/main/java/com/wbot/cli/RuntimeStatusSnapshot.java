package com.wbot.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RuntimeStatusSnapshot {
    public static final class TaskBoardStatus {
        public int running;
        public int pending;
        public int completed;
        public int failed;
        public final List<String> highlightedTasks = new ArrayList<>();
    }

    public String sessionId;
    public String phase = "idle";
    public String phaseLabel = "空闲";
    public String recentAction = "";
    public String lastError = "";
    public String lastErrorPhase = "";
    public long phaseSinceMs = System.currentTimeMillis();
    public int inputTokens;
    public int outputTokens;
    public int totalTokens;
    public double totalCost;
    public final TaskBoardStatus tasks = new TaskBoardStatus();

    public RuntimeStatusSnapshot(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setSession(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setPhase(String phase, String label, String recentAction) {
        this.phase = phase == null || phase.isBlank() ? "running" : phase;
        this.phaseLabel = label == null || label.isBlank() ? "处理中" : label;
        this.phaseSinceMs = System.currentTimeMillis();
        if (recentAction != null) {
            this.recentAction = recentAction;
        }
        if (!"failed".equalsIgnoreCase(this.phase)) {
            this.lastError = "";
            this.lastErrorPhase = "";
        }
    }

    public void markFailed(String message, String phase) {
        this.lastError = message == null ? "" : message;
        this.lastErrorPhase = phase == null || phase.isBlank() ? this.phase : phase;
        setPhase("failed", "执行失败", this.recentAction);
    }

    public void updateUsage(int inputTokens, int outputTokens, double totalCost) {
        this.inputTokens = Math.max(0, inputTokens);
        this.outputTokens = Math.max(0, outputTokens);
        this.totalTokens = Math.max(0, inputTokens + outputTokens);
        this.totalCost = Math.max(0.0, totalCost);
    }

    public void refreshTasks(List<Map<String, Object>> jobs) {
        tasks.running = 0;
        tasks.pending = 0;
        tasks.completed = 0;
        tasks.failed = 0;
        tasks.highlightedTasks.clear();
        for (Map<String, Object> job : jobs) {
            String status = String.valueOf(job.getOrDefault("status", "")).trim().toLowerCase();
            String label = String.valueOf(job.getOrDefault("label", job.getOrDefault("agent_type", job.getOrDefault("id", "-"))));
            String id = String.valueOf(job.getOrDefault("id", ""));
            String display = label + (id.isBlank() ? "" : "#" + id.substring(0, Math.min(8, id.length())));
            switch (status) {
                case "running" -> {
                    tasks.running++;
                    if (tasks.highlightedTasks.size() < 3) {
                        tasks.highlightedTasks.add(display + " 运行中");
                    }
                }
                case "pending" -> tasks.pending++;
                case "completed" -> tasks.completed++;
                case "failed", "timeout" -> {
                    tasks.failed++;
                    if (tasks.highlightedTasks.size() < 3) {
                        tasks.highlightedTasks.add(display + " " + status);
                    }
                }
                default -> {}
            }
        }
    }

    public String spinnerText() {
        long elapsed = Math.max(0, (System.currentTimeMillis() - phaseSinceMs) / 1000);
        StringBuilder sb = new StringBuilder(phaseLabel);
        if (elapsed > 0) {
            sb.append(" ").append(elapsed).append("s");
        }
        if (!recentAction.isBlank()) {
            sb.append(" | 最近动作: ").append(recentAction);
        }
        if (tasks.running > 0 || tasks.pending > 0) {
            sb.append(" | 后台任务: ").append(tasks.running).append(" running");
            if (tasks.pending > 0) {
                sb.append(", ").append(tasks.pending).append(" pending");
            }
        }
        return sb.toString();
    }
}
