package com.aics.retrieval;

import com.aics.common.AicsProperties;
import com.aics.chat.LlmClient;
import com.aics.common.TraceContext;
import com.aics.common.Vectors;
import com.aics.kb.EmbeddingService;
import com.aics.kb.KbChunkRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索链路（技术设计 §3.3）：
 * 改写门控 → embed → 向量 top-k → 阈值过滤 → 单文档限流 → 编号注入。
 */
@Service
public class RetrievalService {

    private final KbChunkRepository chunks;
    private final EmbeddingService embeddings;
    private final QueryRewriter rewriter;
    private final AicsProperties props;

    public RetrievalService(KbChunkRepository chunks, EmbeddingService embeddings, QueryRewriter rewriter,
                            AicsProperties props) {
        this.chunks = chunks;
        this.embeddings = embeddings;
        this.rewriter = rewriter;
        this.props = props;
    }

    public RetrievalResult retrieve(String query, List<LlmClient.PromptMessage> history, boolean awaitingSlot,
                                    TraceContext trace) {
        AicsProperties.Retrieval cfg = props.getRetrieval();
        double threshold = cfg.getScoreThreshold();

        String queryUsed = query;
        boolean rewritten = false;
        if (cfg.isRewriteEnabled() && QueryRewriteGate.shouldRewrite(query, history.size(), awaitingSlot)) {
            try (TraceContext.Stage ignored = trace.stage("查询改写")) {
                String candidate = rewriter.rewrite(query, history);
                rewritten = !candidate.equals(query);
                queryUsed = candidate;
            }
        }
        trace.queryUsed(queryUsed).rewritten(rewritten);

        float[] vector;
        try (TraceContext.Stage ignored = trace.stage("向量化")) {
            vector = embeddings.embedQuery(queryUsed);
        }

        int candidateLimit = Math.max(cfg.getTopK(), cfg.getTopK() * Math.max(1, cfg.getCandidateMultiplier()));
        List<KbChunkRepository.SearchRow> rows;
        try (TraceContext.Stage ignored = trace.stage("向量检索")) {
            rows = chunks.search(Vectors.toLiteral(vector), candidateLimit);
        }

        List<RetrievalResult.Candidate> candidates = new ArrayList<>();
        int rank = 0;
        double topScore = 0;
        if (!rows.isEmpty()) {
            topScore = rows.getFirst().score();
        }
        for (KbChunkRepository.SearchRow row : rows) {
            if (row.score() < threshold) {
                continue;
            }
            candidates.add(new RetrievalResult.Candidate(
                    row.chunkId(), row.docId(), row.filename(), row.headingText(), row.headingPath(),
                    row.content(), round(row.score()), ++rank));
        }

        // 单文档限流：防止上下文被同一篇文档占满
        List<RetrievalResult.RetrievedChunk> refs = new ArrayList<>();
        Map<Long, Integer> perDoc = new HashMap<>();
        for (RetrievalResult.Candidate c : candidates) {
            int used = perDoc.getOrDefault(c.docId(), 0);
            if (used >= cfg.getPerDocLimit()) {
                continue;
            }
            if (refs.size() >= cfg.getTopK()) {
                break;
            }
            perDoc.put(c.docId(), used + 1);
            refs.add(new RetrievalResult.RetrievedChunk(
                    refs.size() + 1, c.chunkId(), c.docId(), c.doc(), c.headingPath(), c.headingText(),
                    c.content(), c.score()));
        }

        trace.topScore(round(topScore));
        return new RetrievalResult(queryUsed, rewritten, round(topScore), threshold, refs, candidates);
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
