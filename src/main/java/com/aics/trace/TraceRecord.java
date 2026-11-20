package com.aics.trace;

import java.util.List;

/** 一次问答的可观测记录（技术设计 §3.8）。 */
public record TraceRecord(
        String id,
        long sessionId,
        Long messageId,
        int turn,
        String queryRaw,
        String queryUsed,
        boolean rewritten,
        double topScore,
        double threshold,
        boolean refused,
        String toolUsed,
        Integer ttftMs,
        Integer totalMs,
        Integer promptTokens,
        Integer completionTokens,
        List<List<Object>> stages) {

    public record RetrievalRow(long chunkId, int rank, double score, boolean cited) {
    }
}
