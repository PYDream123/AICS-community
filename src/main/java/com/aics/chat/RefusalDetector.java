package com.aics.chat;

import java.util.List;

/**
 * L2 兜底：阈值挡住了明显无关的问题，这里挡住「沾边但不足以下结论」的情况。
 *
 * <p>L1（阈值）是系统行为、可度量；L2（模型自觉）只作为补网，标记 refused 并补上转人工入口。
 */
public final class RefusalDetector {

    private static final List<String> MARKERS = List.of(
            "暂无相关资料",
            "暂无相关",
            "没有相关资料",
            "未找到相关",
            "无法回答",
            "没有找到可靠",
            "资料中没有",
            "没有提及");

    private RefusalDetector() {
    }

    public static boolean looksRefused(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String head = answer.length() > 120 ? answer.substring(0, 120) : answer;
        return MARKERS.stream().anyMatch(head::contains);
    }
}
