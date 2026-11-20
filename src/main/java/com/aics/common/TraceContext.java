package com.aics.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 单次问答的 Trace 上下文：阶段计时 + 关键中间结果。
 *
 * <p>阶段计时用 try-with-resources（技术设计 §3.8），避免到处手写 System.currentTimeMillis()。
 */
public final class TraceContext {

    public record StageRecord(String name, long ms) {
    }

    private final String traceId;
    private final long startNanos = System.nanoTime();
    private final List<StageRecord> stages = Collections.synchronizedList(new ArrayList<>());

    private String queryRaw = "";
    private String queryUsed = "";
    private boolean rewritten;
    private double topScore;
    private boolean refused;
    private String toolUsed;
    private Integer promptTokens;
    private Integer completionTokens;

    public TraceContext() {
        this(newTraceId());
    }

    public TraceContext(String traceId) {
        this.traceId = traceId;
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public String traceId() {
        return traceId;
    }

    /** 用法：{@code try (var s = trace.stage("检索")) { ... }} */
    public Stage stage(String name) {
        return new Stage(name);
    }

    /** 已知耗时（例如首字延迟在流结束后才知道）时手动补一条阶段记录。 */
    public void recordStage(String name, long ms) {
        stages.add(new StageRecord(name, ms));
    }

    public long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public List<StageRecord> stageRecords() {
        synchronized (stages) {
            return List.copyOf(stages);
        }
    }

    /** 前端 trace 面板要的是 [[name, ms], ...] 形态（与 demo 数据结构对齐）。 */
    public List<List<Object>> stagesAsList() {
        List<List<Object>> out = new ArrayList<>();
        for (StageRecord r : stageRecords()) {
            out.add(List.of(r.name(), r.ms()));
        }
        return out;
    }

    public Map<String, Object> stagesAsMap() {
        return Map.of("stages", stageRecords());
    }

    public String queryRaw() {
        return queryRaw;
    }

    public TraceContext queryRaw(String value) {
        this.queryRaw = value == null ? "" : value;
        this.queryUsed = this.queryRaw;
        return this;
    }

    public String queryUsed() {
        return queryUsed;
    }

    public TraceContext queryUsed(String value) {
        this.queryUsed = value == null ? "" : value;
        return this;
    }

    public boolean rewritten() {
        return rewritten;
    }

    public TraceContext rewritten(boolean value) {
        this.rewritten = value;
        return this;
    }

    public double topScore() {
        return topScore;
    }

    public TraceContext topScore(double value) {
        this.topScore = value;
        return this;
    }

    public boolean refused() {
        return refused;
    }

    public TraceContext refused(boolean value) {
        this.refused = value;
        return this;
    }

    public String toolUsed() {
        return toolUsed;
    }

    public TraceContext toolUsed(String value) {
        if (value != null && !value.isBlank()) {
            this.toolUsed = this.toolUsed == null ? value : this.toolUsed + "," + value;
        }
        return this;
    }

    public Integer promptTokens() {
        return promptTokens;
    }

    public Integer completionTokens() {
        return completionTokens;
    }

    public TraceContext tokens(Integer prompt, Integer completion) {
        this.promptTokens = prompt;
        this.completionTokens = completion;
        return this;
    }

    public final class Stage implements AutoCloseable {
        private final String name;
        private final long t0 = System.nanoTime();
        private boolean closed;

        private Stage(String name) {
            this.name = name;
        }

        public long elapsedMs() {
            return (System.nanoTime() - t0) / 1_000_000;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            stages.add(new StageRecord(name, elapsedMs()));
        }
    }
}
