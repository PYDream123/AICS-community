package com.aics.chat;

import com.aics.common.AicsProperties;
import com.aics.common.ChatSink;
import com.aics.common.Json;
import com.aics.common.TraceContext;
import com.aics.handoff.HandoffService;
import com.aics.retrieval.RetrievalResult;
import com.aics.retrieval.RetrievalService;
import com.aics.tool.AgentTools;
import com.aics.tool.ToolFacade;
import com.aics.trace.TraceRecord;
import com.aics.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 一次问答的编排核心（技术设计 §3.4）。
 *
 * <p>事件顺序固定：{@code meta → retrieval → (tool_call → tool_result)* → delta* → done}；
 * 拒答分支为 {@code meta → refused → done}。拒绝生成时**不调用模型**——这是 L1 拒答的关键，
 * 拒答准确率才可度量、可回归。
 */
@Service
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    private static final String REFUSAL_TEXT = """
            抱歉，这个问题我在现有资料里没有找到可靠依据。为了避免给出错误信息，我先不作答。

            您可以：
            1. 换一种说法再问一次；
            2. 点击下方按钮由人工客服为您确认。""";

    private static final String GREETING_TEXT = """
            您好，我是售前售后智能助手。

            您可以直接提问产品、售后、物流相关的问题，也可以让我帮您查询订单或创建工单。""";

    private static final Pattern GREETING = Pattern.compile("^(你好|您好|hi|hello|在吗|在么|有人吗)[\\s!！。.~，,]*$",
            Pattern.CASE_INSENSITIVE);

    private final SessionStore store;
    private final MemoryService memory;
    private final RetrievalService retrieval;
    private final PromptBuilder promptBuilder;
    private final LlmClient llm;
    private final AgentTools agentTools;
    private final ToolFacade toolFacade;
    private final HandoffService handoff;
    private final TraceRecorder traceRecorder;
    private final AicsProperties props;

    public ChatOrchestrator(SessionStore store, MemoryService memory, RetrievalService retrieval,
                            PromptBuilder promptBuilder, LlmClient llm, AgentTools agentTools,
                            ToolFacade toolFacade, HandoffService handoff, TraceRecorder traceRecorder,
                            AicsProperties props) {
        this.store = store;
        this.memory = memory;
        this.retrieval = retrieval;
        this.promptBuilder = promptBuilder;
        this.llm = llm;
        this.agentTools = agentTools;
        this.toolFacade = toolFacade;
        this.handoff = handoff;
        this.traceRecorder = traceRecorder;
        this.props = props;
    }

    public record ChatResult(
            long sessionId,
            long messageId,
            int turn,
            String traceId,
            String answer,
            boolean refused,
            boolean suggestedHandoff,
            List<RefView> refs,
            List<Integer> cited,
            long ttftMs,
            long totalMs,
            List<List<Object>> stages,
            Integer promptTokens,
            Integer completionTokens,
            List<RetrievalResult.Candidate> candidates) {
    }

    public ChatResult handle(long sessionId, String userMessage, ChatSink sink) {
        SessionStore.Session session = store.findSession(sessionId)
                .orElseThrow(() -> com.aics.common.AppException.notFound("会话"));
        String question = userMessage == null ? "" : userMessage.strip();
        if (question.isEmpty()) {
            throw com.aics.common.AppException.badRequest("问题不能为空");
        }
        if (question.length() > 2000) {
            question = question.substring(0, 2000);
        }

        TraceContext trace = new TraceContext();
        trace.queryRaw(question);
        List<SessionStore.Message> history = store.messages(sessionId);
        MemoryService.MemoryView memoryView = memory.view(session, history);
        int turn = memoryView.userTurns() + 1;

        int seq = store.nextSeq(sessionId);
        store.appendMessage(sessionId, seq, "user", question, null, null, trace.traceId());
        if (history.isEmpty()) {
            store.touch(sessionId, question.length() > 30 ? question.substring(0, 30) : question);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("traceId", trace.traceId());
        meta.put("sessionId", sessionId);
        meta.put("turn", turn);
        sink.send("meta", meta);

        // 寒暄短路：不走检索，避免「你好」被阈值判成低置信并误转人工
        if (GREETING.matcher(question).matches()) {
            return finishGreeting(sessionId, seq, turn, trace, sink);
        }

        try {
            session = memory.maybeCompact(session, history);
        } catch (RuntimeException e) {
            log.warn("会话摘要压缩失败（忽略，继续使用近期窗口）: {}", e.toString());
        }

        RetrievalResult result;
        try (TraceContext.Stage ignored = trace.stage("检索总耗时")) {
            result = retrieval.retrieve(question, memoryView.history(), awaitingSlot(history), trace);
        }

        if (result.refs().isEmpty()) {
            return finishRefusal(sessionId, seq, turn, trace, result, sink);
        }

        List<RefView> refs = result.refs().stream().map(RefView::of).toList();
        Map<String, Object> retrievalPayload = new LinkedHashMap<>();
        retrievalPayload.put("topScore", result.topScore());
        retrievalPayload.put("threshold", result.threshold());
        retrievalPayload.put("refs", refs);
        sink.send("retrieval", retrievalPayload);

        String systemPrompt;
        try (TraceContext.Stage ignored = trace.stage("Prompt 组装")) {
            systemPrompt = promptBuilder.systemPrompt(session.summary(), result.refs());
        }

        ToolFacade.CallContext callContext =
                new ToolFacade.CallContext(sessionId, trace.traceId(), sink, trace);
        LlmClient.StreamRequest request = new LlmClient.StreamRequest(
                systemPrompt,
                memoryView.history(),
                question,
                List.of(agentTools),
                Map.of(AgentTools.CTX_KEY, callContext));

        long t0 = System.nanoTime();
        StreamOutcome streamed = streamTokens(request, sink, trace);
        long totalMs = (System.nanoTime() - t0) / 1_000_000;

        if (streamed.error() != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", "LLM_ERROR");
            payload.put("message", "回答生成失败，请稍后重试或转人工");
            sink.send("error", payload);
            recordTrace(sessionId, null, turn, trace, result, List.of(), streamed, totalMs, false);
            return new ChatResult(sessionId, -1, turn, trace.traceId(), "", false, true, refs, List.of(),
                    streamed.ttftMs(), totalMs, trace.stagesAsList(), streamed.promptTokens(),
                    streamed.completionTokens(), result.candidates());
        }

        String answer = streamed.text().strip();
        List<Integer> cited = CitationParser.parse(answer, refs.size());
        boolean l2Refused = RefusalDetector.looksRefused(answer);
        boolean toolFailed = callContext.consecutiveFailures() >= props.getTool().getMaxConsecutiveFailures();
        boolean suggestHandoff = l2Refused || toolFailed;

        trace.refused(l2Refused);
        trace.tokens(streamed.promptTokens(), streamed.completionTokens());
        trace.recordStage("模型首字", streamed.ttftMs());
        trace.recordStage("流式生成", Math.max(0, totalMs - streamed.ttftMs()));

        long messageId = store.appendMessage(sessionId, store.nextSeq(sessionId), "assistant", answer,
                refs.isEmpty() ? null : Json.write(refs), null, trace.traceId());

        if (suggestHandoff) {
            handoff.suggest(sessionId,
                    l2Refused ? HandoffService.ReasonCode.LOW_CONFIDENCE : HandoffService.ReasonCode.TOOL_FAILURE,
                    question);
        }

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("messageId", messageId);
        done.put("tokens", Map.of("in", nullSafe(streamed.promptTokens()), "out", nullSafe(streamed.completionTokens())));
        done.put("stages", trace.stagesAsList());
        done.put("cited", cited);
        done.put("ttftMs", streamed.ttftMs());
        done.put("totalMs", totalMs);
        done.put("refused", l2Refused);
        done.put("suggestHandoff", suggestHandoff);
        sink.send("done", done);

        recordTrace(sessionId, messageId, turn, trace, result, cited, streamed, totalMs, l2Refused);

        return new ChatResult(sessionId, messageId, turn, trace.traceId(), answer, l2Refused, suggestHandoff,
                refs, cited, streamed.ttftMs(), totalMs, trace.stagesAsList(), streamed.promptTokens(),
                streamed.completionTokens(), result.candidates());
    }

    // ---------------------------------------------------------------- 分支收尾

    private ChatResult finishGreeting(long sessionId, int seq, int turn, TraceContext trace, ChatSink sink) {
        trace.refused(false);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("text", GREETING_TEXT);
        sink.send("delta", delta);
        long messageId = store.appendMessage(sessionId, seq + 1, "assistant", GREETING_TEXT, null, null,
                trace.traceId());
        trace.recordStage("寒暄短路", trace.elapsedMs());

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("messageId", messageId);
        done.put("tokens", Map.of("in", 0, "out", 0));
        done.put("stages", trace.stagesAsList());
        done.put("cited", List.of());
        done.put("ttftMs", 0);
        done.put("totalMs", trace.elapsedMs());
        done.put("refused", false);
        done.put("suggestHandoff", false);
        sink.send("done", done);

        recordTrace(sessionId, messageId, turn, trace, RetrievalResult.empty(),
                List.of(), new StreamOutcome(GREETING_TEXT, 0, 0, 0, null), trace.elapsedMs(), false);
        return new ChatResult(sessionId, messageId, turn, trace.traceId(), GREETING_TEXT, false, false,
                List.of(), List.of(), 0, trace.elapsedMs(), trace.stagesAsList(), 0, 0, List.of());
    }

    /** L1 拒答：检索最高分低于阈值，不调用生成模型，直接返回固定话术并建议转人工。 */
    private ChatResult finishRefusal(long sessionId, int seq, int turn, TraceContext trace,
                                     RetrievalResult result, ChatSink sink) {
        trace.refused(true);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", "LOW_CONFIDENCE");
        payload.put("topScore", result.topScore());
        payload.put("threshold", result.threshold());
        payload.put("suggestHandoff", true);
        payload.put("message", REFUSAL_TEXT);
        sink.send("refused", payload);

        handoff.suggest(sessionId, HandoffService.ReasonCode.LOW_CONFIDENCE, trace.queryRaw());

        long messageId = store.appendMessage(sessionId, store.nextSeq(sessionId), "assistant", REFUSAL_TEXT,
                null, null, trace.traceId());

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("messageId", messageId);
        done.put("tokens", Map.of("in", 0, "out", 0));
        done.put("stages", trace.stagesAsList());
        done.put("cited", List.of());
        done.put("ttftMs", 0);
        done.put("totalMs", trace.elapsedMs());
        done.put("refused", true);
        done.put("suggestHandoff", true);
        sink.send("done", done);

        recordTrace(sessionId, messageId, turn, trace, result, List.of(),
                new StreamOutcome(REFUSAL_TEXT, 0, 0, 0, null), trace.elapsedMs(), true);

        return new ChatResult(sessionId, messageId, turn, trace.traceId(), REFUSAL_TEXT, true, true,
                List.of(), List.of(), 0, trace.elapsedMs(), trace.stagesAsList(), 0, 0, result.candidates());
    }

    // ---------------------------------------------------------------- 流式

    private record StreamOutcome(String text, long ttftMs, Integer promptTokens, Integer completionTokens,
                                 Throwable error) {
    }

    private StreamOutcome streamTokens(LlmClient.StreamRequest request, ChatSink sink, TraceContext trace) {
        StringBuilder answer = new StringBuilder();
        long[] firstTokenAt = {0};
        long start = System.nanoTime();
        Integer[] promptTokens = {null};
        Integer[] completionTokens = {null};

        CompletableFuture<Void> finished = new CompletableFuture<>();
        AtomicReference<Disposable> subscription = new AtomicReference<>();

        Disposable disposable = llm.stream(request).subscribe(
                delta -> {
                    try {
                        if (delta.text() != null) {
                            if (firstTokenAt[0] == 0) {
                                firstTokenAt[0] = System.nanoTime();
                            }
                            answer.append(delta.text());
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("text", delta.text());
                            sink.send("delta", payload);
                        }
                        if (delta.promptTokens() != null) {
                            promptTokens[0] = delta.promptTokens();
                        }
                        if (delta.completionTokens() != null) {
                            completionTokens[0] = delta.completionTokens();
                        }
                        if (sink.isClosed()) {
                            Disposable d = subscription.get();
                            if (d != null) {
                                d.dispose();
                            }
                            finished.complete(null);
                        }
                    } catch (RuntimeException e) {
                        finished.completeExceptionally(e);
                    }
                },
                error -> finished.completeExceptionally(error),
                () -> finished.complete(null));

        subscription.set(disposable);
        // 客户端断开：dispose 订阅，避免模型继续吐字计费
        sink.whenClosed(() -> {
            Disposable d = subscription.get();
            if (d != null) {
                d.dispose();
            }
            finished.complete(null);
        });

        try {
            finished.get(props.getSse().getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            disposable.dispose();
            log.warn("流式生成中断: {}", e.toString());
            return new StreamOutcome(answer.toString(), elapsed(firstTokenAt[0], start), promptTokens[0],
                    completionTokens[0], e);
        }

        long ttft = firstTokenAt[0] == 0 ? 0 : (firstTokenAt[0] - start) / 1_000_000;
        return new StreamOutcome(answer.toString(), ttft, promptTokens[0], completionTokens[0], null);
    }

    private static long elapsed(long firstTokenAt, long start) {
        return firstTokenAt == 0 ? 0 : (firstTokenAt - start) / 1_000_000;
    }

    // ---------------------------------------------------------------- 落库

    private void recordTrace(long sessionId, Long messageId, int turn, TraceContext trace,
                             RetrievalResult result, List<Integer> cited, StreamOutcome streamed,
                             long totalMs, boolean refused) {
        Set<Long> citedChunkIds = new HashSet<>();
        for (RetrievalResult.RetrievedChunk ref : result.refs()) {
            if (cited.contains(ref.index())) {
                citedChunkIds.add(ref.chunkId());
            }
        }
        List<TraceRecord.RetrievalRow> rows = new ArrayList<>();
        for (RetrievalResult.Candidate c : result.candidates()) {
            rows.add(new TraceRecord.RetrievalRow(c.chunkId(), c.rank(), c.score(),
                    citedChunkIds.contains(c.chunkId())));
        }
        TraceRecord record = new TraceRecord(
                trace.traceId(), sessionId, messageId, turn,
                trace.queryRaw(), trace.queryUsed(), trace.rewritten(),
                round(trace.topScore()), round(result.threshold()), refused, trace.toolUsed(),
                (int) streamed.ttftMs(), (int) totalMs,
                streamed.promptTokens(), streamed.completionTokens(), trace.stagesAsList());
        traceRecorder.record(record, rows);
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    /** 上一轮是否在等订单号：用于决定要不要做查询改写。 */
    static boolean awaitingSlot(List<SessionStore.Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            SessionStore.Message m = history.get(i);
            if ("assistant".equals(m.role())) {
                String c = m.content() == null ? "" : m.content();
                return c.contains("订单号") && (c.contains("请提供") || c.contains("发给我")
                        || c.contains("告诉我") || c.contains("是多少"));
            }
        }
        return false;
    }
}
