package com.wbot.subagent;

import com.wbot.util.Jsons;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SubagentManager {
    public record SubagentJob(
            String id,
            String label,
            String task,
            String agentType,
            String status,
            String createdAt,
            String updatedAt,
            String threadId,
            String error,
            String finalResponse,
            double durationSeconds) {}

    @FunctionalInterface
    public interface JobRunner {
        String run(SubagentJob job) throws Exception;
    }

    private static final class JobState {
        private final String id;
        private final String label;
        private final String task;
        private final String agentType;
        private final String createdAt;
        private final String threadId;
        private volatile String status;
        private volatile String updatedAt;
        private volatile String error;
        private volatile String finalResponse;
        private volatile double durationSeconds;

        private JobState(String id, String label, String task, String agentType, String createdAt, String threadId) {
            this.id = id;
            this.label = label;
            this.task = task;
            this.agentType = agentType;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
            this.threadId = threadId;
            this.status = "pending";
            this.error = "";
            this.finalResponse = "";
            this.durationSeconds = 0.0;
        }

        private synchronized void updateStatus(String status) {
            this.status = status;
            this.updatedAt = OffsetDateTime.now().toString();
        }

        private synchronized void complete(String status, String finalResponse, String error, double durationSeconds) {
            this.status = status;
            this.finalResponse = finalResponse == null ? "" : finalResponse;
            this.error = error == null ? "" : error;
            this.durationSeconds = durationSeconds;
            this.updatedAt = OffsetDateTime.now().toString();
        }

        private SubagentJob snapshot() {
            return new SubagentJob(id, label, task, agentType, status, createdAt, updatedAt, threadId, error, finalResponse, durationSeconds);
        }
    }

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, JobState> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<SubagentJob>> futures = new ConcurrentHashMap<>();
    private final Path journalPath;
    private final JobRunner runner;

    public SubagentManager(Path workspaceRoot, JobRunner runner) {
        this.journalPath = workspaceRoot.resolve(".w-bot-java-subagents.jsonl");
        this.runner = runner;
    }

    public SubagentJob spawn(String task, String label, String agentType, String threadId) {
        String id = UUID.randomUUID().toString().replace("-", "");
        String now = OffsetDateTime.now().toString();
        JobState state = new JobState(id, label == null ? "" : label, task, agentType == null || agentType.isBlank() ? "worker" : agentType, now, threadId);
        jobs.put(id, state);
        CompletableFuture<SubagentJob> future = CompletableFuture.supplyAsync(() -> runJob(state), executor);
        futures.put(id, future);
        appendEvent(state.snapshot(), "created");
        return state.snapshot();
    }

    public List<Map<String, Object>> list(String statusFilter, int limit) {
        String normalized = statusFilter == null ? "" : statusFilter.trim().toLowerCase();
        return jobs.values().stream()
                .map(JobState::snapshot)
                .filter(job -> normalized.isBlank() || job.status().toLowerCase().equals(normalized))
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .limit(Math.max(1, limit))
                .map(this::toMap)
                .toList();
    }

    public Map<String, Object> waitFor(String id, int timeoutSeconds) {
        CompletableFuture<SubagentJob> future = futures.get(id);
        JobState state = jobs.get(id);
        if (future == null || state == null) {
            return Map.of("id", id, "status", "not_found", "error", "Unknown subagent id: " + id);
        }
        try {
            SubagentJob job = timeoutSeconds <= 0
                    ? state.snapshot()
                    : future.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            return toMap(job);
        } catch (java.util.concurrent.TimeoutException e) {
            return toMap(state.snapshot());
        } catch (Exception e) {
            return Map.of("id", id, "status", "failed", "error", e.getMessage());
        }
    }

    private SubagentJob runJob(JobState state) {
        state.updateStatus("running");
        appendEvent(state.snapshot(), "started");
        long startedAt = System.nanoTime();
        try {
            String reply = runner.run(state.snapshot());
            double seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
            state.complete("completed", reply, "", seconds);
        } catch (Exception e) {
            double seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
            state.complete("failed", "", e.getClass().getSimpleName() + ": " + e.getMessage(), seconds);
        }
        appendEvent(state.snapshot(), state.snapshot().status());
        return state.snapshot();
    }

    private Map<String, Object> toMap(SubagentJob job) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", job.id());
        out.put("label", job.label());
        out.put("task", job.task());
        out.put("agent_type", job.agentType());
        out.put("status", job.status());
        out.put("created_at", job.createdAt());
        out.put("updated_at", job.updatedAt());
        out.put("thread_id", job.threadId());
        out.put("error", job.error());
        out.put("final_response", job.finalResponse());
        out.put("duration_seconds", job.durationSeconds());
        return out;
    }

    private void appendEvent(SubagentJob job, String event) {
        try {
            if (journalPath.getParent() != null) {
                Files.createDirectories(journalPath.getParent());
            }
            Map<String, Object> payload = new LinkedHashMap<>(toMap(job));
            payload.put("event", event);
            Files.writeString(
                    journalPath,
                    Jsons.toJson(payload) + "\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
