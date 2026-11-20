package com.aics.tool;

import com.aics.common.AicsProperties;
import com.aics.common.CollectingSink;
import com.aics.common.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 工具门面：参数校验、错误码、幂等回放、超时、脱敏（技术设计 §3.5）。 */
class ToolFacadeTest {

    private AicsProperties props;
    private ToolInvocationRepository repo;
    private FakeOrderService orders;
    private ThreadPoolTaskExecutor executor;
    private ToolFacade facade;
    private CollectingSink sink;
    private ToolFacade.CallContext ctx;

    @BeforeEach
    void setUp() {
        props = new AicsProperties();
        repo = mock(ToolInvocationRepository.class);
        orders = new FakeOrderService();
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.initialize();
        facade = new ToolFacade(repo, orders, props, executor);

        sink = new CollectingSink();
        TraceContext trace = new TraceContext();
        ctx = new ToolFacade.CallContext(7L, trace.traceId(), sink, trace);
        when(repo.reserve(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("参数格式错误返回 INVALID_ARGS，且不发起调用、不推工具卡片")
    void invalidArgsAreNotInvoked() {
        ToolFacade.Outcome outcome = facade.execute(ToolFacade.QUERY_ORDER, Map.of("orderNo", "12345"), ctx);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.code()).isEqualTo(ToolErrorCode.INVALID_ARGS.name());
        assertThat(sink.names()).doesNotContain("tool_call");
        assertThat(orders.findOrderCalls).isZero();
        // 模型看到的是「去追问」，不是技术错误
        assertThat(outcome.toModelJson()).contains("instruction").doesNotContain("Exception");
    }

    @Test
    @DisplayName("订单不存在 → ORDER_NOT_FOUND，并给出用户可见话术")
    void orderNotFound() {
        ToolFacade.Outcome outcome = facade.execute(ToolFacade.QUERY_ORDER,
                Map.of("orderNo", "SO202611209901"), ctx);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.code()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(outcome.toModelJson()).contains("未查询到该订单");

        var result = sink.first("tool_result");
        assertThat(result).isNotNull();
        assertThat(result.payload().get("errorCode")).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("订单已取消 → ORDER_CANCELLED")
    void orderCancelled() {
        orders.put("SO202611080051", "CANCELLED");

        ToolFacade.Outcome outcome = facade.execute(ToolFacade.QUERY_ORDER,
                Map.of("orderNo", "SO202611080051"), ctx);

        assertThat(outcome.code()).isEqualTo("ORDER_CANCELLED");
    }

    @Test
    @DisplayName("未发货订单查物流 → NO_LOGISTICS，而不是查询失败")
    void noLogistics() {
        orders.put("SO202611190061", "PAID");

        ToolFacade.Outcome outcome = facade.execute(ToolFacade.QUERY_LOGISTICS,
                Map.of("orderNo", "SO202611190061"), ctx);

        assertThat(outcome.code()).isEqualTo("NO_LOGISTICS");
        assertThat(outcome.toModelJson()).contains("订单尚未发货");
    }

    @Test
    @DisplayName("正常查询返回结构化字段与展示行，事件顺序为 tool_call → tool_result")
    void successfulQuery() {
        orders.put("SO202611180032", "SHIPPED");
        orders.logistics.put("SO202611180032",
                new OrderService.LogisticsInfo("顺丰速运", "SF1234567890123", "派送中", OffsetDateTime.now()));

        ToolFacade.Outcome outcome = facade.execute(ToolFacade.QUERY_ORDER,
                Map.of("orderNo", "so202611180032"), ctx);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.data()).containsEntry("status", "已发货");
        assertThat(outcome.rows()).anySatisfy(row -> assertThat(row.get(0)).isEqualTo("订单号"));
        assertThat(sink.names()).containsExactly("tool_call", "tool_result");
        assertThat(outcome.toModelJson()).contains("\"ok\":true");
    }

    @Test
    @DisplayName("幂等命中：直接回放已存结果，不再执行业务服务")
    void replaysStoredResult() {
        when(repo.reserve(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        when(repo.findByKey(anyLong(), anyString(), anyString())).thenReturn(Optional.of(
                new ToolInvocationRepository.Stored("OK", null,
                        "{\"ticketNo\":\"TK202611210001\",\"etaHours\":24}", 88, false)));

        ToolFacade.Outcome outcome = facade.execute(ToolFacade.CREATE_TICKET,
                Map.of("description", "门锁无法上锁", "contact", "13812345678"), ctx);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.data()).containsEntry("ticketNo", "TK202611210001");
        assertThat(orders.createTicketCalls).isZero();
        assertThat(sink.first("tool_result").payload().get("replayed")).isEqualTo(true);
    }

    @Test
    @DisplayName("同一请求正在处理中 → DUPLICATE_IN_FLIGHT，不重复写入")
    void duplicateInFlight() {
        when(repo.reserve(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        when(repo.findByKey(anyLong(), anyString(), anyString())).thenReturn(Optional.of(
                new ToolInvocationRepository.Stored("IN_PROGRESS", null, null, 0, false)));

        ToolFacade.Outcome outcome = facade.execute(ToolFacade.CREATE_TICKET,
                Map.of("description", "门锁无法上锁", "contact", "13812345678"), ctx);

        assertThat(outcome.code()).isEqualTo("DUPLICATE_IN_FLIGHT");
        assertThat(orders.createTicketCalls).isZero();
    }

    @Test
    @DisplayName("相同参数两次调用使用同一个幂等键")
    void sameArgsSameKey() {
        Map<String, Object> args = Map.of("description", "门锁无法上锁", "contact", "13812345678");

        facade.execute(ToolFacade.CREATE_TICKET, args, ctx);
        facade.execute(ToolFacade.CREATE_TICKET, args, ctx);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(repo, org.mockito.Mockito.times(2))
                .reserve(anyLong(), anyString(), anyString(), keys.capture(), anyString());
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
    }

    @Test
    @DisplayName("落库参数已脱敏：手机号不出现明文")
    void masksArgsBeforePersisting() {
        facade.execute(ToolFacade.CREATE_TICKET,
                Map.of("description", "门锁无法上锁", "contact", "13812345678"), ctx);

        ArgumentCaptor<String> masked = ArgumentCaptor.forClass(String.class);
        verify(repo).reserve(anyLong(), anyString(), anyString(), anyString(), masked.capture());
        assertThat(masked.getValue()).doesNotContain("13812345678").contains("138****5678");
    }

    @Test
    @DisplayName("下游超时 → DOWNSTREAM_TIMEOUT，用户看到可理解的降级话术")
    void downstreamTimeout() {
        props.getTool().setTimeoutMs(60);
        orders.sleepMs = 400;
        orders.put("SO202611180032", "SHIPPED");

        ToolFacade.Outcome outcome = facade.execute(ToolFacade.QUERY_ORDER,
                Map.of("orderNo", "SO202611180032"), ctx);

        assertThat(outcome.code()).isEqualTo("DOWNSTREAM_TIMEOUT");
        assertThat(outcome.toModelJson()).contains("查询暂时没有响应");
    }

    @Test
    @DisplayName("未知工具不进入业务层")
    void unknownTool() {
        facade.execute("drop_database", Map.of(), ctx);
        assertThat(orders.findOrderCalls).isZero();
        verify(repo, never()).reserve(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- fake

    static class FakeOrderService implements OrderService {
        final Map<String, OrderInfo> registry = new HashMap<>();
        final Map<String, LogisticsInfo> logistics = new HashMap<>();
        int findOrderCalls;
        int createTicketCalls;
        long sleepMs;

        void put(String orderNo, String status) {
            registry.put(orderNo, new OrderInfo(orderNo, status, OffsetDateTime.now(),
                    new BigDecimal("1299.00"), "智能门锁 Pro × 1", "顺丰速运", "SF1234567890123"));
        }

        @Override
        public Optional<OrderInfo> findOrder(String orderNo) {
            findOrderCalls++;
            sleep();
            return Optional.ofNullable(registry.get(orderNo));
        }

        @Override
        public Optional<LogisticsInfo> findLatestLogistics(String orderNo) {
            sleep();
            return Optional.ofNullable(logistics.get(orderNo));
        }

        @Override
        public CreatedTicket createTicket(long sessionId, String description, String contact) {
            createTicketCalls++;
            return new CreatedTicket("TK202611210001", 24);
        }

        private void sleep() {
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
