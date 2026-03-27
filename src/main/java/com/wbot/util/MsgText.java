package com.wbot.util;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import java.util.stream.Collectors;

public final class MsgText {
    private MsgText() {}

    public static String extract(Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return "";
        }
        String thinking = msg.getContent().stream()
                .filter(c -> c instanceof ThinkingBlock)
                .map(c -> ((ThinkingBlock) c).getThinking())
                .collect(Collectors.joining("\n"));

        String text = msg.getContent().stream()
                .filter(c -> c instanceof TextBlock)
                .map(c -> ((TextBlock) c).getText())
                .collect(Collectors.joining("\n"));

        if (!text.isBlank() && !thinking.isBlank()) {
            return thinking + "\n\n" + text;
        }
        if (!text.isBlank()) {
            return text;
        }
        return thinking;
    }
}
