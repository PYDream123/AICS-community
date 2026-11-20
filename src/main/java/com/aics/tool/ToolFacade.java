package com.aics.tool;

import com.aics.common.AicsProperties;
import com.aics.common.ChatSink;
import com.aics.common.IdempotencyKeys;
import com.aics.common.Json;
import com.aics.common.Masking;
import com.aics.common.TraceContext;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 工具门面（技术设计 §3.5.1）：参数校验 → 幂等 → 超时 → 错误码映射 → 事件推送 → 落库脱敏。
 *
 * <p>@Tool 方法只负责让模型看见 schema，真正的执行入口全在这里，便于测试与加策略。
 */
@Service
public class ToolFacade {

    private static final Logger log = LoggerFactory.getLogger(ToolFacade.class);

    public static final String QUERY_ORDER = "query_order";
    public static final String QUERY_LOGISTICS = "query_logistics";
    public static final String CREATE_TICKET = "create_ticket";

    private static final Pattern ORDER_NO = Pattern.compile("^SO\\d{12}$");
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+\\.[\\w.]+$");

    private final ToolInvocationRepository invocations;
    private final OrderService orders;
    private final AicsProperties props;
    private final ThreadPoolTaskExecutor toolExecutor;

    public ToolFacade(ToolInvocationRepository invocations, OrderService orders, AicsProperties props,
                      @Qualifier("toolExecutor") ThreadPoolTaskExecutor toolExecutor) {
        this.invocations = invocations;
        this.orders = orders;
        this.props = props;
        this.toolExecutor = toolExecutor;
    }

    /** 一次请求内的工具上下文：sessionId / traceId / 事件出口 / 连续失败计数。 */
    public static final class CallContext {
        private final long sessionId;
        private final String traceId;
        private final ChatSink sink;
        private final TraceContext trace;
        private final AtomicInteger consecutiveFailures = new AtomicInteger();

        public CallContext(long sessionId, String traceId, ChatSink sink, TraceContext trace) {
            this.sessionId = sessionId;
            this.traceId = traceId;
            this.sink = sink;
            this.trace = trace;
        }

        public long sessionId() {
            return sessionId;
        }

        public String traceId() {
            return traceId;
        }

        public ChatSink sink() {
            return sink;
        }

        public TraceContext trace() {
            return trace;
        }

        public int consecutiveFailures() {
            return consecutiveFailures.get();
        }

        public void resetFailures() {
            consecutiveFailures.set(0);
        }

        public int recordFailure() {
            return consecutiveFailures.incrementAndGet();
        }
    }

    public record Outcome(boolean ok, String code, Map<String, Object> data, List<List<String>> rows,
                          boolean replayed, long costMs) {

        /** 给模型看的结构化结果；错误码 + 可执行提示，不暴露技术细节。 */
        public String toModelJson() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", ok);
            payload.put("code", code);
            if (ok) {
                payload.put("data", data);
            } else {
                ToolErrorCode ec = safeCode(code);
                if (ec.userVisible()) {
                    payload.put("user_message", ec.userMessage());
                }
                if (ec == ToolErrorCode.INVALID_ARGS) {
                    payload.put("instruction", "参数缺失或格式不正确。请向用户索取正确的信息后重试，不要编造结果。");
                }
                if (ec == ToolErrorCode.DUPLICATE_IN_FLIGHT) {
                    payload.put("instruction", "同一请求正在处理中，请告知用户稍后查看结果，不要重复提交。");
                }
            }
            return Json.write(payload);
        }

        private static ToolErrorCode safeCode(String code) {
            try {
                return ToolErrorCode.valueOf(code);
            } catch (IllegalArgumentException e) {
                return ToolErrorCode.INTERNAL_ERROR;
            }
        }
    }

    public Outcome execute(String toolName, Map<String, Object> rawArgs, CallContext ctx) {
        long started = System.nanoTime();
        Map<String, Object> args = new LinkedHashMap<>(rawArgs == null ? Map.of() : rawArgs);
        String callId = ctx.traceId().substring(0, 8) + "-" + Math.abs(args.hashCode() % 1000);

        // 先做格式归一（大小写、首尾空白）再校验：模型偶尔会输出小写订单号，不该判成非法参数
        normalize(toolName, args);
        List<String> problems = validate(toolName, args);
        if (!problems.isEmpty()) {
            // 参数问题不推 tool_call 卡片：这是模型该追问的事，不是一次「调用」
            log.debug("工具参数校验未通过 {}: {}", toolName, problems);
            return new Outcome(false, ToolErrorCode.INVALID_ARGS.name(), Map.of(), List.of(), false,
                    elapsedMs(started));
        }

        ctx.sink().send("tool_call", eventPayload(callId, toolName, args, "running"));

        String idemKey = IdempotencyKeys.of(ctx.sessionId(), toolName, args);
        String masked = Json.write(Masking.maskArgs(args));

        if (!invocations.reserve(ctx.sessionId(), ctx.traceId(), toolName, idemKey, masked)) {
            Optional<ToolInvocationRepository.Stored> stored = invocations.findByKey(ctx.sessionId(), toolName, idemKey);
            if (stored.isPresent() && "OK".equals(stored.get().status())) {
                // 幂等命中：直接返回上次结果，并标记 replayed
                Outcome replay = replayOf(stored.get(), ctx, callId, toolName);
                return replay;
            }
            if (stored.isPresent() && "IN_PROGRESS".equals(stored.get().status())) {
                Outcome inFlight = new Outcome(false, ToolErrorCode.DUPLICATE_IN_FLIGHT.name(), Map.of(), List.of(),
                        true, elapsedMs(started));
                pushResult(ctx, callId, toolName, inFlight, started);
                return inFlight;
            }
            // 之前失败过：允许重试（写入类在失败时并未落业务数据，重试安全）
        }

        Outcome outcome = runWithTimeout(toolName, args, ctx);
        ctx.trace().toolUsed(toolName);
        if (outcome.ok()) {
            ctx.resetFailures();
        } else if (!ToolErrorCode.INVALID_ARGS.name().equals(outcome.code())) {
            ctx.recordFailure();
        }

        String status = outcome.ok() ? "OK"
                : ToolErrorCode.DOWNSTREAM_TIMEOUT.name().equals(outcome.code()) ? "TIMEOUT" : "ERROR";
        try {
            invocations.complete(ctx.sessionId(), toolName, idemKey, status, outcome.code(),
                    outcome.ok() ? Json.write(outcome.data()) : null,
                    (int) outcome.costMs(), false);
        } catch (RuntimeException e) {
            log.warn("工具调用落库失败（不影响主流程）: {}", e.toString());
        }

        pushResult(ctx, callId, toolName, outcome, started);
        return outcome;
    }

    private Outcome runWithTimeout(String toolName, Map<String, Object> args, CallContext ctx) {
        long started = System.nanoTime();
        Future<Outcome> future = toolExecutor.submit(() -> invoke(toolName, args, ctx));
        try {
            return future.get(props.getTool().getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("工具超时 {}（{}ms）", toolName, props.getTool().getTimeoutMs());
            return fail(ToolErrorCode.DOWNSTREAM_TIMEOUT, elapsedMs(started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(ToolErrorCode.INTERNAL_ERROR, elapsedMs(started));
        } catch (ExecutionException e) {
            log.error("工具执行异常 {}", toolName, e.getCause());
            return fail(ToolErrorCode.INTERNAL_ERROR, elapsedMs(started));
        }
    }

    private Outcome invoke(String toolName, Map<String, Object> args, CallContext ctx) {
        long started = System.nanoTime();
        try {
            return switch (toolName) {
                case QUERY_ORDER -> queryOrder(args, started);
                case QUERY_LOGISTICS -> queryLogistics(args, started);
                case CREATE_TICKET -> createTicket(args, ctx, started);
                default -> fail(ToolErrorCode.INTERNAL_ERROR, elapsedMs(started));
            };
        } catch (RuntimeException e) {
            log.error("工具 {} 下游异常", toolName, e);
            return fail(ToolErrorCode.INTERNAL_ERROR, elapsedMs(started));
        }
    }

    private Outcome queryOrder(Map<String, Object> args, long started) {
        String orderNo = (String) args.get("orderNo");
        Optional<OrderService.OrderInfo> found = orders.findOrder(orderNo);
        if (found.isEmpty()) {
            return fail(ToolErrorCode.ORDER_NOT_FOUND, elapsedMs(started));
        }
        OrderService.OrderInfo order = found.get();
        if ("CANCELLED".equals(order.status())) {
            return fail(ToolErrorCode.ORDER_CANCELLED, elapsedMs(started));
        }
        String statusText = MockOrderService.statusText(order.status());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", order.orderNo());
        data.put("status", statusText);
        data.put("statusCode", order.status());
        data.put("placedAt", order.placedAt() == null ? "" :
                order.placedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        data.put("amount", order.amount() == null ? "0.00" : order.amount().setScale(2, RoundingMode.HALF_UP).toString());
        data.put("itemSummary", order.itemSummary());

        List<List<String>> rows = List.of(
                List.of("订单号", order.orderNo()),
                List.of("状态", statusText),
                List.of("下单时间", String.valueOf(data.get("placedAt"))),
                List.of("实付金额", "¥ " + data.get("amount")),
                List.of("商品", order.itemSummary()));
        return new Outcome(true, ToolErrorCode.OK.name(), data, rows, false, elapsedMs(started));
    }

    private Outcome queryLogistics(Map<String, Object> args, long started) {
        String orderNo = (String) args.get("orderNo");
        Optional<OrderService.OrderInfo> found = orders.findOrder(orderNo);
        if (found.isEmpty()) {
            return fail(ToolErrorCode.ORDER_NOT_FOUND, elapsedMs(started));
        }
        if ("CANCELLED".equals(found.get().status())) {
            return fail(ToolErrorCode.ORDER_CANCELLED, elapsedMs(started));
        }
        Optional<OrderService.LogisticsInfo> latest = orders.findLatestLogistics(orderNo);
        if (latest.isEmpty()) {
            // 已付款未发货：没有节点就是 NO_LOGISTICS，而不是「查询失败」
            return fail(ToolErrorCode.NO_LOGISTICS, elapsedMs(started));
        }
        OrderService.LogisticsInfo info = latest.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("carrier", info.carrier());
        data.put("waybillNo", info.waybillNo());
        data.put("latestNode", info.latestNode());
        data.put("latestNodeTime", info.latestNodeTime() == null ? "" :
                info.latestNodeTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

        List<List<String>> rows = List.of(
                List.of("承运商", String.valueOf(info.carrier())),
                List.of("运单号", String.valueOf(info.waybillNo())),
                List.of("最新节点", String.valueOf(info.latestNode())),
                List.of("节点时间", String.valueOf(data.get("latestNodeTime"))));
        return new Outcome(true, ToolErrorCode.OK.name(), data, rows, false, elapsedMs(started));
    }

    private Outcome createTicket(Map<String, Object> args, CallContext ctx, long started) {
        String description = (String) args.get("description");
        String contact = (String) args.get("contact");
        OrderService.CreatedTicket ticket = orders.createTicket(ctx.sessionId(), description, contact);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketNo", ticket.ticketNo());
        data.put("etaHours", ticket.etaHours());
        List<List<String>> rows = List.of(
                List.of("工单号", ticket.ticketNo()),
                List.of("预计响应", ticket.etaHours() + " 小时内"));
        return new Outcome(true, ToolErrorCode.OK.name(), data, rows, false, elapsedMs(started));
    }

    private Outcome replayOf(ToolInvocationRepository.Stored stored, CallContext ctx, String callId, String toolName) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (stored.resultJson() != null) {
            try {
                data.putAll(Json.mapper().readValue(stored.resultJson(), new TypeReference<Map<String, Object>>() {
                }));
            } catch (Exception e) {
                log.debug("回放结果解析失败: {}", e.toString());
            }
        }
        List<List<String>> rows = new ArrayList<>();
        data.forEach((k, v) -> rows.add(List.of(k, String.valueOf(v))));
        Outcome outcome = new Outcome(true, ToolErrorCode.OK.name(), data, rows, true, stored.latencyMs());
        ctx.sink().send("tool_result", Map.of(
                "callId", callId,
                "name", toolName,
                "ok", true,
                "costMs", stored.latencyMs(),
                "replayed", true,
                "rows", rows));
        return outcome;
    }

    private void pushResult(CallContext ctx, String callId, String toolName, Outcome outcome, long started) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callId", callId);
        payload.put("name", toolName);
        payload.put("ok", outcome.ok());
        payload.put("costMs", outcome.costMs());
        payload.put("rows", outcome.rows());
        if (!outcome.ok()) {
            payload.put("errorCode", outcome.code());
            ToolErrorCode code = Outcome.safeCode(outcome.code());
            if (code.userVisible()) {
                payload.put("message", code.userMessage());
            }
        }
        ctx.sink().send("tool_result", payload);
    }

    private Map<String, Object> eventPayload(String callId, String toolName, Map<String, Object> args, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callId", callId);
        payload.put("name", toolName);
        payload.put("args", Json.write(args));
        payload.put("status", status);
        return payload;
    }

    private Outcome fail(ToolErrorCode code, long costMs) {
        return new Outcome(false, code.name(), Map.of(), List.of(), false, costMs);
    }

    // ---------------------------------------------------------------- 校验

    private List<String> validate(String toolName, Map<String, Object> args) {
        List<String> problems = new ArrayList<>();
        switch (toolName) {
            case QUERY_ORDER, QUERY_LOGISTICS -> {
                String orderNo = str(args.get("orderNo"));
                if (orderNo.isBlank()) {
                    problems.add("缺少 orderNo");
                } else if (!ORDER_NO.matcher(orderNo).matches()) {
                    problems.add("orderNo 格式不正确");
                }
            }
            case CREATE_TICKET -> {
                String description = str(args.get("description"));
                String contact = str(args.get("contact"));
                if (description.isBlank()) {
                    problems.add("缺少 description");
                } else if (description.length() > 500) {
                    problems.add("description 过长");
                }
                if (contact.isBlank()) {
                    problems.add("缺少 contact");
                } else if (!PHONE.matcher(contact).matches() && !EMAIL.matcher(contact).matches()) {
                    problems.add("contact 需为手机号或邮箱");
                }
            }
            default -> problems.add("未知工具");
        }
        return problems;
    }

    private void normalize(String toolName, Map<String, Object> args) {
        if (args.containsKey("orderNo") && args.get("orderNo") instanceof String s) {
            args.put("orderNo", s.trim().toUpperCase(Locale.ROOT));
        }
        if (args.containsKey("contact") && args.get("contact") instanceof String s) {
            args.put("contact", s.trim());
        }
        if (args.containsKey("description") && args.get("description") instanceof String s) {
            args.put("description", s.trim());
        }
        if (CREATE_TICKET.equals(toolName) && !args.containsKey("sessionId")) {
            args.remove("sessionId");
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    /** 供测试与展示：把金额转字符串不丢精度。 */
    static String amountText(BigDecimal amount) {
        return amount == null ? "0.00" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
