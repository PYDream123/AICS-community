package com.aics.chat;

import com.aics.common.AicsProperties;
import com.aics.common.CollectingSink;
import com.aics.handoff.HandoffService;
import com.aics.retrieval.RetrievalResult;
import com.aics.retrieval.RetrievalService;
import com.aics.support.FakeLlmClient;
import com.aics.support.InMemorySessionStore;
import com.aics.tool.AgentTools;
import com.aics.tool.OrderService;
import com.aics.tool.ToolFacade;
import com.aics.tool.ToolInvocationRepository;
import com.aics.trace.TraceRecord;
import com.aics.trace.TraceRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排契约测试（技术设计 §9.3）：保护事件顺序与「阈值之下不调用模型」这两条容易在重构中被破坏的约定。
 */
class ChatOrchestratorTest {

    private InMemorySessionStore store;
    private FakeLlmClient llm;
    private RetrievalService retrieval;
    private HandoffService handoff;
    private CapturingTraceRecorder traceRecorder;
    private ChatOrchestrator orchestrator;
    private ToolFacade facade;
    private ThreadPoolTaskExecutor toolExecutor;
    private long sessionId;

    @BeforeEach
    void setUp() {
        AicsProperties props = new AicsProperties();
        store = new InMemorySessionStore();
        llm = new FakeLlmClient();
        retrieval = mock(RetrievalService.class);
        handoff = mock(HandoffService.class);
        traceRecorder = new CapturingTraceRecorder();

        MemoryService memory = new MemoryService(llm, store, props);
        PromptBuilder promptBuilder = new PromptBuilder(props);

        toolExecutor = new ThreadPoolTaskExecutor();
        toolExecutor.setCorePoolSize(2);
        toolExecutor.setMaxPoolSize(2);
        toolExecutor.setQueueCapacity(10);
        toolExecutor.initialize();

        ToolInvocationRepository invocationRepo = mock(ToolInvocationRepository.class);
        when(invocationRepo.reserve(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        FakeOrderService orders = new FakeOrderService();
        orders.put("SO202611180032");
        facade = new ToolFacade(invocationRepo, orders, props, toolExecutor);
        AgentTools agentTools = new AgentTools(facade);

        orchestrator = new ChatOrchestrator(store, memory, retrieval, promptBuilder, llm, agentTools,
                facade, handoff, traceRecorder, props);
        sessionId = store.createSession("测试会话");
    }

    @Test
    @DisplayName("阈值之下：meta → refused → done，且完全不调用生成模型")
    void refusesBelowThresholdWithoutCallingModel() {
        when(retrieval.retrieve(any(), any(), anyBoolean(), any()))
                .thenReturn(new RetrievalResult("你们公司在东南亚有代理商吗", false, 0.32, 0.70,
                        List.of(), List.of()));

        CollectingSink sink = new CollectingSink();
        ChatOrchestrator.ChatResult result = orchestrator.handle(sessionId, "你们公司在东南亚有代理商吗", sink);

        assertThat(sink.names()).containsExactly("meta", "refused", "done");
        assertThat(llm.streamCalls).isZero();
        assertThat(result.refused()).isTrue();
        assertThat(result.suggestedHandoff()).isTrue();
        verify(handoff).suggest(eq(sessionId), eq(HandoffService.ReasonCode.LOW_CONFIDENCE), anyString());
        assertThat(traceRecorder.records).hasSize(1);
        assertThat(traceRecorder.records.getFirst().refused()).isTrue();
    }

    @Test
    @DisplayName("命中知识库：retrieval 先于 delta，回答带引用回填")
    void streamsAnswerWithCitations() {
        when(retrieval.retrieve(any(), any(), anyBoolean(), any())).thenReturn(hitResult());
        llm.answer = "整机保修 12 个月，主要部件 24 个月 [1]。";

        CollectingSink sink = new CollectingSink();
        ChatOrchestrator.ChatResult result = orchestrator.handle(sessionId, "你们的保修期多久", sink);

        List<String> names = sink.names();
        assertThat(names.getFirst()).isEqualTo("meta");
        assertThat(names.get(1)).isEqualTo("retrieval");
        assertThat(names.getLast()).isEqualTo("done");
        assertThat(names).contains("delta");

        assertThat(result.answer()).isEqualTo(llm.answer);
        assertThat(result.cited()).containsExactly(1);
        assertThat(result.refs()).hasSize(1);
        assertThat(sink.concatenatedDelta()).isEqualTo(llm.answer);

        // 引用与召回明细都落了 Trace
        TraceRecord record = traceRecorder.records.getFirst();
        List<Long> citedChunkIds = traceRecorder.rows.getFirst().stream()
                .filter(TraceRecord.RetrievalRow::cited)
                .map(TraceRecord.RetrievalRow::chunkId)
                .toList();
        assertThat(citedChunkIds).containsExactly(101L);
        assertThat(record.promptTokens()).isEqualTo(620);
    }

    @Test
    @DisplayName("工具调用：tool_call / tool_result 必须出现在第一个 delta 之前")
    void toolEventsPrecedeText() {
        when(retrieval.retrieve(any(), any(), anyBoolean(), any())).thenReturn(hitResult());
        llm.answer = "您的订单已发货，最新节点为派送中 [1]。";
        llm.beforeStream = request -> {
            ToolFacade.CallContext ctx =
                    (ToolFacade.CallContext) request.toolContext().get(AgentTools.CTX_KEY);
            facade.execute(ToolFacade.QUERY_ORDER, Map.of("orderNo", "SO202611180032"), ctx);
        };

        CollectingSink sink = new CollectingSink();
        orchestrator.handle(sessionId, "我的订单到哪了 SO202611180032", sink);

        List<String> names = sink.names();
        int toolCall = names.indexOf("tool_call");
        int toolResult = names.indexOf("tool_result");
        int firstDelta = names.indexOf("delta");
        assertThat(toolCall).isGreaterThan(0);
        assertThat(toolResult).isGreaterThan(toolCall);
        assertThat(toolResult).isLessThan(firstDelta);
    }

    @Test
    @DisplayName("模型自述「暂无相关资料」→ 标记拒答并建议转人工（L2 兜底）")
    void detectsModelRefusal() {
        when(retrieval.retrieve(any(), any(), anyBoolean(), any())).thenReturn(hitResult());
        llm.answer = "抱歉，关于该问题暂无相关资料。";

        CollectingSink sink = new CollectingSink();
        ChatOrchestrator.ChatResult result = orchestrator.handle(sessionId, "门锁能当扶手用吗", sink);

        assertThat(result.refused()).isTrue();
        assertThat(result.suggestedHandoff()).isTrue();
        verify(handoff).suggest(eq(sessionId), eq(HandoffService.ReasonCode.LOW_CONFIDENCE), anyString());
        assertThat(sink.first("done").payload().get("refused")).isEqualTo(true);
    }

    @Test
    @DisplayName("模型异常：只推 error，不落半截回答")
    void streamErrorSendsErrorEvent() {
        when(retrieval.retrieve(any(), any(), anyBoolean(), any())).thenReturn(hitResult());
        llm.streamError = new IllegalStateException("upstream 502");

        CollectingSink sink = new CollectingSink();
        ChatOrchestrator.ChatResult result = orchestrator.handle(sessionId, "你们的保修期多久", sink);

        assertThat(sink.names()).contains("error");
        assertThat(result.answer()).isEmpty();
        assertThat(store.messages(sessionId)).hasSize(1); // 只有用户消息
    }

    @Test
    @DisplayName("寒暄短路：不检索、不生成，也不写入转人工队列")
    void greetingShortCircuits() {
        CollectingSink sink = new CollectingSink();
        ChatOrchestrator.ChatResult result = orchestrator.handle(sessionId, "你好", sink);

        assertThat(sink.names()).doesNotContain("retrieval");
        assertThat(sink.names()).contains("delta", "done");
        assertThat(llm.streamCalls).isZero();
        assertThat(result.refused()).isFalse();
        verify(handoff, org.mockito.Mockito.never()).suggest(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("会话历史落库：用户与助手消息都持久化，刷新可恢复")
    void persistsConversation() {
        when(retrieval.retrieve(any(), any(), anyBoolean(), any())).thenReturn(hitResult());

        orchestrator.handle(sessionId, "你们的保修期多久", new CollectingSink());

        List<SessionStore.Message> messages = store.messages(sessionId);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("user");
        assertThat(messages.get(1).role()).isEqualTo("assistant");
        assertThat(messages.get(1).refsJson()).contains("产品保修条款.md");
    }

    // ---------------------------------------------------------------- helpers

    private RetrievalResult hitResult() {
        RetrievalResult.RetrievedChunk ref = new RetrievalResult.RetrievedChunk(
                1, 101L, 1L, "产品保修条款.md", "1. 保修范围与期限 > 1.1 保修期限", "1.1 保修期限",
                "整机保修 12 个月，主板、屏幕、电池等主要部件保修 24 个月。", 0.93);
        RetrievalResult.Candidate candidate = new RetrievalResult.Candidate(
                101L, 1L, "产品保修条款.md", "1.1 保修期限", "1. 保修范围与期限 > 1.1 保修期限",
                "整机保修 12 个月，主板、屏幕、电池等主要部件保修 24 个月。", 0.93, 1);
        return new RetrievalResult("你们的保修期多久", false, 0.93, 0.70, List.of(ref), List.of(candidate));
    }

    static class CapturingTraceRecorder implements TraceRecorder {
        final List<TraceRecord> records = new ArrayList<>();
        final List<List<TraceRecord.RetrievalRow>> rows = new ArrayList<>();

        @Override
        public void record(TraceRecord record, List<TraceRecord.RetrievalRow> retrievals) {
            records.add(record);
            rows.add(retrievals);
        }

        @Override
        public Optional<TraceView> find(String traceId) {
            return Optional.empty();
        }

        @Override
        public List<TraceView> recent(int limit) {
            return List.of();
        }
    }

    static class FakeOrderService implements OrderService {
        private boolean present;

        void put(String orderNo) {
            present = true;
        }

        @Override
        public Optional<OrderInfo> findOrder(String orderNo) {
            if (!present) {
                return Optional.empty();
            }
            return Optional.of(new OrderInfo(orderNo, "SHIPPED", OffsetDateTime.now(),
                    new BigDecimal("1299.00"), "智能门锁 Pro × 1", "顺丰速运", "SF1234567890123"));
        }

        @Override
        public Optional<LogisticsInfo> findLatestLogistics(String orderNo) {
            return Optional.of(new LogisticsInfo("顺丰速运", "SF1234567890123", "派送中", OffsetDateTime.now()));
        }

        @Override
        public CreatedTicket createTicket(long sessionId, String description, String contact) {
            return new CreatedTicket("TK202611210001", 24);
        }
    }
}
