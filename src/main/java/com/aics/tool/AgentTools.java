package com.aics.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型可见的工具定义（技术设计 §6.4）。
 *
 * <p>工具描述直接决定模型会不会正确调用，比系统提示更关键。尤其是「若用户未提供订单号，
 * 不要调用本工具，先向用户索取」这句——不加这句，模型倾向用空参数硬调，再由错误码兜底，
 * 用户体验会差一个档次。
 */
@Component
public class AgentTools {

    /** 通过 ToolContext 传参而不是 ThreadLocal：工具可能执行在 Reactor 线程上。 */
    public static final String CTX_KEY = "toolCallContext";

    private final ToolFacade facade;

    public AgentTools(ToolFacade facade) {
        this.facade = facade;
    }

    @Tool(name = ToolFacade.QUERY_ORDER, description = """
            查询订单的详细信息：订单状态、下单时间、金额、商品摘要。
            仅当用户询问订单状态、金额、下单时间，且已提供订单号时调用。
            订单号格式为 SO 加 12 位数字，如 SO202611180032。
            若用户未提供订单号，不要调用本工具，先向用户索取。
            """)
    public String queryOrder(ToolContext context,
                             @ToolParam(description = "订单号，格式为 SO 加 12 位数字，例如 SO202611180032", required = true)
                             String orderNo) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("orderNo", orderNo);
        return facade.execute(ToolFacade.QUERY_ORDER, args, contextOf(context)).toModelJson();
    }

    @Tool(name = ToolFacade.QUERY_LOGISTICS, description = """
            查询订单的物流信息：承运商、运单号、最新物流节点。
            仅当用户询问包裹到哪里了、是否发货、什么时候能收到，且已提供订单号时调用。
            订单号格式为 SO 加 12 位数字。若用户未提供订单号，不要调用本工具，先向用户索取。
            """)
    public String queryLogistics(ToolContext context,
                                 @ToolParam(description = "订单号，格式为 SO 加 12 位数字", required = true)
                                 String orderNo) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("orderNo", orderNo);
        return facade.execute(ToolFacade.QUERY_LOGISTICS, args, contextOf(context)).toModelJson();
    }

    @Tool(name = ToolFacade.CREATE_TICKET, description = """
            提交售后工单，由人工客服在承诺时限内跟进。
            仅当用户明确要求报修、投诉、要求人工处理，且已同时提供问题描述与联系方式时调用。
            缺少联系方式时不要调用，先向用户索取手机号或邮箱。
            同一会话内重复提交同样的内容不会产生新工单，会直接返回已有工单号。
            """)
    public String createTicket(ToolContext context,
                               @ToolParam(description = "问题描述，需包含产品、故障现象或诉求", required = true)
                               String description,
                               @ToolParam(description = "用户联系方式，手机号或邮箱", required = true)
                               String contact) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("description", description);
        args.put("contact", contact);
        return facade.execute(ToolFacade.CREATE_TICKET, args, contextOf(context)).toModelJson();
    }

    private ToolFacade.CallContext contextOf(ToolContext context) {
        if (context == null || context.getContext() == null) {
            throw new IllegalStateException("工具上下文缺失：调用方未通过 toolContext 传入请求上下文");
        }
        Object value = context.getContext().get(CTX_KEY);
        if (value instanceof ToolFacade.CallContext callContext) {
            return callContext;
        }
        throw new IllegalStateException("工具上下文类型不正确");
    }
}
