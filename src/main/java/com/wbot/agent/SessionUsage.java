package com.wbot.agent;

public record SessionUsage(
        int inputTokens,
        int outputTokens,
        int cacheCreationInputTokens,
        int cacheReadInputTokens,
        int totalTokens,
        double estimatedCostUsd) {}
