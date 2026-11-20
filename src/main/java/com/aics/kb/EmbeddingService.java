package com.aics.kb;

import com.aics.common.AicsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 调用封装：批量、重试、维度校验。
 *
 * <p>维度写死 1024（与 kb_chunk.embedding vector(1024) 一致），换模型若维度不同必须配套迁移。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingModel embeddingModel;
    private final AicsProperties props;

    public EmbeddingService(EmbeddingModel embeddingModel, AicsProperties props) {
        this.embeddingModel = embeddingModel;
        this.props = props;
    }

    public float[] embedQuery(String text) {
        return embedWithRetry(List.of(text)).getFirst();
    }

    public List<float[]> embedDocuments(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        int batchSize = Math.max(1, props.getEmbedding().getBatchSize());
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(texts.size(), from + batchSize);
            out.addAll(embedWithRetry(texts.subList(from, to)));
        }
        return out;
    }

    private List<float[]> embedWithRetry(List<String> batch) {
        int maxRetries = Math.max(0, props.getEmbedding().getMaxRetries());
        RuntimeException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                List<float[]> vectors = embeddingModel.embed(batch);
                validate(batch.size(), vectors);
                return vectors;
            } catch (RuntimeException e) {
                // 维度/配置类错误重试也没用，直接抛给上层给出可读提示
                if (e instanceof IllegalStateException && String.valueOf(e.getMessage()).contains("维度")) {
                    throw e;
                }
                last = e;
                if (attempt < maxRetries) {
                    long backoff = 200L << attempt;
                    log.warn("Embedding 调用失败（第 {} 次），{}ms 后重试: {}", attempt + 1, backoff, e.toString());
                    sleep(backoff);
                }
            }
        }
        throw new EmbeddingUnavailableException("向量化服务暂时不可用", last);
    }

    private void validate(int expected, List<float[]> vectors) {
        if (vectors.size() != expected) {
            throw new IllegalStateException("Embedding 返回条数与请求不一致: " + vectors.size() + " != " + expected);
        }
        int expectedDims = props.getEmbedding().getDimensions();
        for (float[] v : vectors) {
            if (v.length != expectedDims) {
                throw new IllegalStateException(
                        "Embedding 维度不匹配：返回 " + v.length + "，配置为 " + expectedDims
                                + "。更换模型需同步迁移 kb_chunk.embedding 列并重算全量向量");
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 下游不可用：对外只给用户可理解的话术，堆栈留在日志。 */
    public static class EmbeddingUnavailableException extends RuntimeException {
        public EmbeddingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
