package com.aics.tool;

/**
 * 错误码与用户话术分离（技术设计 §3.5.3）：模型看到结构化码，用户看到人话。
 *
 * <p>{@code INVALID_ARGS} 不直接答用户，交由模型追问补齐槽位。
 */
public enum ToolErrorCode {

    OK(""),
    ORDER_NOT_FOUND("未查询到该订单，请确认订单号是否正确"),
    ORDER_CANCELLED("该订单已取消，如有疑问可以为您转接人工"),
    NO_LOGISTICS("订单尚未发货，暂无物流信息"),
    DOWNSTREAM_TIMEOUT("查询暂时没有响应，请稍后再试"),
    INVALID_ARGS(""),
    DUPLICATE_IN_FLIGHT("请求正在处理中，请稍后查看结果"),
    INTERNAL_ERROR("服务暂时不可用，请稍后再试");

    private final String userMessage;

    ToolErrorCode(String userMessage) {
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }

    public boolean userVisible() {
        return !userMessage.isEmpty();
    }
}
