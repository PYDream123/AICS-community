package com.aics.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全部可调参数集中在此（产品文档非功能要求「不写死在代码里」，技术设计 §7）。
 */
@ConfigurationProperties(prefix = "aics")
public class AicsProperties {

    private final Llm llm = new Llm();
    private final Embedding embedding = new Embedding();
    private final Retrieval retrieval = new Retrieval();
    private final Chunking chunking = new Chunking();
    private final Memory memory = new Memory();
    private final Prompt prompt = new Prompt();
    private final Tool tool = new Tool();
    private final Sse sse = new Sse();

    public Llm getLlm() {
        return llm;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public Chunking getChunking() {
        return chunking;
    }

    public Memory getMemory() {
        return memory;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    public Tool getTool() {
        return tool;
    }

    public Sse getSse() {
        return sse;
    }

    public static class Llm {
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String completionsPath = "/v1/chat/completions";
        private String model = "deepseek-flash";
        private double temperature = 0.2;
        private int maxTokens = 1024;
        /** enabled / disabled，disabled 时通过 extraBody 显式关闭 thinking */
        private String thinking = "disabled";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getCompletionsPath() {
            return completionsPath;
        }

        public void setCompletionsPath(String completionsPath) {
            this.completionsPath = completionsPath;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public String getThinking() {
            return thinking;
        }

        public void setThinking(String thinking) {
            this.thinking = thinking;
        }

        public boolean thinkingDisabled() {
            return "disabled".equalsIgnoreCase(thinking);
        }
    }

    public static class Embedding {
        private String baseUrl = "";
        private String apiKey = "";
        private String embeddingsPath = "/embeddings";
        private String model = "BAAI/bge-m3";
        private int dimensions = 1024;
        private int batchSize = 16;
        private int maxRetries = 2;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getEmbeddingsPath() {
            return embeddingsPath;
        }

        public void setEmbeddingsPath(String embeddingsPath) {
            this.embeddingsPath = embeddingsPath;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }
    }

    public static class Retrieval {
        private int topK = 6;
        private double scoreThreshold = 0.70;
        private int perDocLimit = 3;
        private int candidateMultiplier = 3;
        private boolean rewriteEnabled = true;

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getScoreThreshold() {
            return scoreThreshold;
        }

        public void setScoreThreshold(double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
        }

        public int getPerDocLimit() {
            return perDocLimit;
        }

        public void setPerDocLimit(int perDocLimit) {
            this.perDocLimit = perDocLimit;
        }

        public int getCandidateMultiplier() {
            return candidateMultiplier;
        }

        public void setCandidateMultiplier(int candidateMultiplier) {
            this.candidateMultiplier = candidateMultiplier;
        }

        public boolean isRewriteEnabled() {
            return rewriteEnabled;
        }

        public void setRewriteEnabled(boolean rewriteEnabled) {
            this.rewriteEnabled = rewriteEnabled;
        }
    }

    public static class Chunking {
        private int maxChars = 800;
        private int minChars = 120;
        private int overlapChars = 80;
        private int maxHeadingLevel = 3;

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }

        public int getMinChars() {
            return minChars;
        }

        public void setMinChars(int minChars) {
            this.minChars = minChars;
        }

        public int getOverlapChars() {
            return overlapChars;
        }

        public void setOverlapChars(int overlapChars) {
            this.overlapChars = overlapChars;
        }

        public int getMaxHeadingLevel() {
            return maxHeadingLevel;
        }

        public void setMaxHeadingLevel(int maxHeadingLevel) {
            this.maxHeadingLevel = maxHeadingLevel;
        }
    }

    public static class Memory {
        private int recentTurns = 6;
        private int summaryTriggerTurns = 12;
        private int summaryMaxChars = 300;

        public int getRecentTurns() {
            return recentTurns;
        }

        public void setRecentTurns(int recentTurns) {
            this.recentTurns = recentTurns;
        }

        public int getSummaryTriggerTurns() {
            return summaryTriggerTurns;
        }

        public void setSummaryTriggerTurns(int summaryTriggerTurns) {
            this.summaryTriggerTurns = summaryTriggerTurns;
        }

        public int getSummaryMaxChars() {
            return summaryMaxChars;
        }

        public void setSummaryMaxChars(int summaryMaxChars) {
            this.summaryMaxChars = summaryMaxChars;
        }
    }

    public static class Prompt {
        private String companyName = "示例科技";
        private int maxChunkCharsInPrompt = 500;

        public String getCompanyName() {
            return companyName;
        }

        public void setCompanyName(String companyName) {
            this.companyName = companyName;
        }

        public int getMaxChunkCharsInPrompt() {
            return maxChunkCharsInPrompt;
        }

        public void setMaxChunkCharsInPrompt(int maxChunkCharsInPrompt) {
            this.maxChunkCharsInPrompt = maxChunkCharsInPrompt;
        }
    }

    public static class Tool {
        private int timeoutMs = 3000;
        private int maxConsecutiveFailures = 2;

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getMaxConsecutiveFailures() {
            return maxConsecutiveFailures;
        }

        public void setMaxConsecutiveFailures(int maxConsecutiveFailures) {
            this.maxConsecutiveFailures = maxConsecutiveFailures;
        }
    }

    public static class Sse {
        private long timeoutMs = 180_000;
        private long heartbeatMs = 15_000;

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public long getHeartbeatMs() {
            return heartbeatMs;
        }

        public void setHeartbeatMs(long heartbeatMs) {
            this.heartbeatMs = heartbeatMs;
        }
    }
}
