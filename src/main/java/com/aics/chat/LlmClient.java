package com.aics.chat;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 模型调用的唯一出口。实现类才依赖 Spring AI，编排与测试都不需要感知框架细节。
 *
 * <p>对应技术设计 §1.5：Spring AI 只托管「模型调用 + 流式输出 + 工具循环」，其余自研。
 */
public interface LlmClient {

    record PromptMessage(String role, String content) {
        public static PromptMessage user(String content) {
            return new PromptMessage("user", content);
        }

        public static PromptMessage assistant(String content) {
            return new PromptMessage("assistant", content);
        }
    }

    /**
     * @param tools       @Tool 标注的对象，由 Spring AI 负责生成 schema 并执行多轮工具循环
     * @param toolContext 通过 ToolContext 显式传给工具方法（不能用 ThreadLocal，工具可能跑在 Reactor 线程）
     */
    record StreamRequest(
            String systemPrompt,
            List<PromptMessage> history,
            String userMessage,
            List<Object> tools,
            Map<String, Object> toolContext) {
    }

    record ChatDelta(String text, Integer promptTokens, Integer completionTokens) {
    }

    record CompleteRequest(String systemPrompt, String userMessage, double temperature, int maxTokens) {
    }

    /** 流式生成；工具调用期间的中间轮次由框架内部消化，不会产生 delta。 */
    Flux<ChatDelta> stream(StreamRequest request);

    /** 非流式短调用：查询改写、会话摘要。 */
    String complete(CompleteRequest request);
}
