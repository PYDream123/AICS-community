package com.aics.retrieval;

import java.util.List;

/** 检索结果：refs 用于 Prompt 与前端引用条，candidates 用于 Trace 与 Recall@k 计算。 */
public record RetrievalResult(
        String queryUsed,
        boolean rewritten,
        double topScore,
        double threshold,
        List<RetrievedChunk> refs,
        List<Candidate> candidates) {

    public boolean belowThreshold() {
        return refs.isEmpty();
    }

    public static RetrievalResult empty() {
        return new RetrievalResult("", false, 0, 0, List.of(), List.of());
    }

    /** 进入 Prompt 的片段，index 即引用编号 [1..n]。 */
    public record RetrievedChunk(
            int index,
            long chunkId,
            long docId,
            String doc,
            String headingPath,
            String headingText,
            String content,
            double score) {
    }

    /** 检索原始排序（阈值过滤后、单文档限流前），rank 从 1 开始。 */
    public record Candidate(long chunkId, long docId, String doc, String headingText, String headingPath,
                            String content, double score, int rank) {
    }
}
