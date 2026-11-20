package com.aics.trace;

import java.util.List;
import java.util.Optional;

public interface TraceRecorder {

    /** 异步落库；实现必须吞掉异常——Trace 是旁路，不能成为可用性的单点。 */
    void record(TraceRecord record, List<TraceRecord.RetrievalRow> retrievals);

    Optional<TraceView> find(String traceId);

    List<TraceView> recent(int limit);

    record TraceView(
            String traceId,
            long sessionId,
            int turn,
            String query,
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
            List<List<Object>> stages,
            List<RetrievalDetail> retrievals) {
    }

    record RetrievalDetail(long chunkId, int rank, double score, boolean cited,
                           String doc, String section) {
    }
}
