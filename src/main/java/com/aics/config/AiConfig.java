package com.aics.config;

import com.aics.common.AicsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.HashMap;
import java.util.Map;

/**
 * 双 provider 装配（技术设计 §1.3）：DeepSeek 只提供对话，Embedding 必须独立配置。
 *
 * <p>两者都是 OpenAI 兼容协议，切换供应商只改 base-url / model / api-key。
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    public OpenAiApi chatOpenAiApi(AicsProperties props) {
        AicsProperties.Llm llm = props.getLlm();
        if (isBlank(llm.getBaseUrl())) {
            throw new IllegalStateException("aics.llm.base-url 不能为空：对话模型必须显式配置");
        }
        if (isBlank(llm.getApiKey())) {
            log.warn("aics.llm.api-key 未配置（DEEPSEEK_API_KEY 为空），对话接口会在调用时返回 401");
        }
        log.info("对话模型 provider={} model={} thinking={}", llm.getBaseUrl(), llm.getModel(), llm.getThinking());
        return OpenAiApi.builder()
                .baseUrl(trimTrailingSlash(llm.getBaseUrl()))
                .apiKey(isBlank(llm.getApiKey()) ? "not-configured" : llm.getApiKey())
                .completionsPath(llm.getCompletionsPath())
                .build();
    }

    @Bean
    public OpenAiChatOptions chatOptions(AicsProperties props) {
        AicsProperties.Llm llm = props.getLlm();
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(llm.getModel())
                .temperature(llm.getTemperature())
                .maxTokens(llm.getMaxTokens())
                // 流式也要拿到 usage，否则 Trace 里 token 一栏永远是空
                .streamUsage(true);

        Map<String, Object> extra = new HashMap<>();
        if (llm.thinkingDisabled()) {
            // 显式关闭思考模式：V4 系列默认开启，会把首字延迟翻倍（技术设计 §1.3）
            extra.put("thinking", Map.of("type", "disabled"));
        }
        if (!extra.isEmpty()) {
            builder.extraBody(extra);
        }
        return builder.build();
    }

    @Bean
    public ChatModel chatModel(OpenAiApi chatOpenAiApi, OpenAiChatOptions chatOptions) {
        return OpenAiChatModel.builder()
                .openAiApi(chatOpenAiApi)
                .defaultOptions(chatOptions)
                .toolCallingManager(ToolCallingManager.builder().build())
                .build();
    }

    @Bean
    @Primary
    public ChatClient chatClient(ChatModel chatModel, OpenAiChatOptions chatOptions) {
        return ChatClient.builder(chatModel).defaultOptions(chatOptions).build();
    }

    @Bean
    public OpenAiApi embeddingOpenAiApi(AicsProperties props) {
        AicsProperties.Embedding emb = props.getEmbedding();
        if (isBlank(emb.getBaseUrl())) {
            // 这条校验写进风险表：DeepSeek 没有 Embedding 接口，漏配会到入库时才炸
            throw new IllegalStateException("aics.embedding.base-url 不能为空：DeepSeek 不提供 Embedding，必须指定独立 provider");
        }
        if (isBlank(emb.getApiKey())) {
            log.warn("aics.embedding.api-key 未配置（EMBEDDING_API_KEY 为空），知识入库与检索会在调用时失败");
        }
        log.info("向量模型 provider={} model={} dims={}", emb.getBaseUrl(), emb.getModel(), emb.getDimensions());
        if (emb.getDimensions() != 1024) {
            log.warn("aics.embedding.dimensions={} 与 kb_chunk.embedding vector(1024) 不一致，需配套迁移重建列",
                    emb.getDimensions());
        }
        return OpenAiApi.builder()
                .baseUrl(trimTrailingSlash(emb.getBaseUrl()))
                .apiKey(isBlank(emb.getApiKey()) ? "not-configured" : emb.getApiKey())
                .embeddingsPath(emb.getEmbeddingsPath())
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel(OpenAiApi embeddingOpenAiApi, AicsProperties props) {
        return new OpenAiEmbeddingModel(
                embeddingOpenAiApi,
                MetadataMode.NONE,
                OpenAiEmbeddingOptions.builder()
                        .model(props.getEmbedding().getModel())
                        .dimensions(props.getEmbedding().getDimensions())
                        .build());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
