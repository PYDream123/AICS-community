package com.aics.chat;

import com.aics.common.AicsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 适配层。
 *
 * <p>关键点：
 * <ul>
 *   <li>工具由框架自动执行并续在同一段 Flux 上，因此「工具执行期间没有 token」天然就是
 *       「先出工具卡片、再出文字」的顺序（技术设计 §3.4.2）；</li>
 *   <li>toolContext 显式传参，工具方法拿得到 sink 与 trace，不依赖 ThreadLocal；</li>
 *   <li>只取 content 作为 delta，reasoning_content 不会进入正文（技术设计 §1.3 的硬要求）。</li>
 * </ul>
 */
@Component
public class SpringAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClient.class);

    private final ChatClient chatClient;
    private final OpenAiChatOptions defaultOptions;
    private final AicsProperties props;

    public SpringAiLlmClient(ChatClient chatClient, OpenAiChatOptions defaultOptions, AicsProperties props) {
        this.chatClient = chatClient;
        this.defaultOptions = defaultOptions;
        this.props = props;
    }

    @Override
    public Flux<ChatDelta> stream(StreamRequest request) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(request.systemPrompt())
                .messages(toMessages(request.history()))
                .user(request.userMessage());

        if (request.tools() != null && !request.tools().isEmpty()) {
            spec = spec.tools(request.tools().toArray());
        }
        if (request.toolContext() != null && !request.toolContext().isEmpty()) {
            spec = spec.toolContext(request.toolContext());
        }

        return spec.stream().chatResponse()
                .map(this::toDelta)
                // 只为 usage 出现的空 chunk 也要让上层看到，否则 token 统计会丢
                .filter(d -> d.text() != null || d.promptTokens() != null || d.completionTokens() != null)
                .doOnError(e -> log.warn("模型流式调用失败: {}", e.toString()));
    }

    @Override
    public String complete(CompleteRequest request) {
        OpenAiChatOptions options = OpenAiChatOptions.fromOptions(defaultOptions);
        options.setTemperature(request.temperature());
        options.setMaxTokens(request.maxTokens());
        try {
            return chatClient.prompt()
                    .system(request.systemPrompt())
                    .user(request.userMessage())
                    .options(options)
                    .call()
                    .content();
        } catch (RuntimeException e) {
            log.warn("模型短调用失败（改写/摘要降级为原文）: {}", e.toString());
            return null;
        }
    }

    private ChatDelta toDelta(ChatResponse response) {
        String text = null;
        if (response.getResult() != null && response.getResult().getOutput() != null) {
            text = response.getResult().getOutput().getText();
            if (text != null && text.isEmpty()) {
                text = null;
            }
        }
        Integer promptTokens = null;
        Integer completionTokens = null;
        if (response.getMetadata() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                promptTokens = usage.getPromptTokens();
                completionTokens = usage.getCompletionTokens();
            }
        }
        return new ChatDelta(text, promptTokens, completionTokens);
    }

    private List<Message> toMessages(List<PromptMessage> history) {
        List<Message> messages = new ArrayList<>();
        if (history == null) {
            return messages;
        }
        for (PromptMessage m : history) {
            if (m.content() == null || m.content().isBlank()) {
                continue;
            }
            messages.add(switch (m.role()) {
                case "assistant" -> new AssistantMessage(m.content());
                case "system" -> new SystemMessage(m.content());
                default -> new UserMessage(m.content());
            });
        }
        return messages;
    }

    /** 供启动日志打印实际模型 ID，避免沿用已下线模型却毫无察觉。 */
    public String modelId() {
        return props.getLlm().getModel();
    }
}
