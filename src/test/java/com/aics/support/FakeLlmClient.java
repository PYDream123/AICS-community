package com.aics.support;

import com.aics.chat.LlmClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 假的模型客户端：不联网，按预设吐字。
 *
 * <p>{@code beforeStream} 用来模拟「模型决定调工具」——Spring AI 的真实行为是工具执行完后
 * 才继续吐字，这里用同样的顺序即可验证编排的事件顺序。
 */
public class FakeLlmClient implements LlmClient {

    public final List<StreamRequest> streamRequests = new ArrayList<>();
    public final List<CompleteRequest> completeRequests = new ArrayList<>();

    public String answer = "整机保修 12 个月，主要部件 24 个月 [1]。";
    public String completeResult = "保修期外维修收费标准";
    public int streamCalls;
    public RuntimeException streamError;
    public Consumer<StreamRequest> beforeStream;
    public Integer promptTokens = 620;
    public Integer completionTokens = 42;

    @Override
    public Flux<ChatDelta> stream(StreamRequest request) {
        streamCalls++;
        streamRequests.add(request);
        if (beforeStream != null) {
            beforeStream.accept(request);
        }
        if (streamError != null) {
            return Flux.error(streamError);
        }
        List<ChatDelta> deltas = new ArrayList<>();
        int step = Math.max(1, answer.length() / 3);
        for (int i = 0; i < answer.length(); i += step) {
            deltas.add(new ChatDelta(answer.substring(i, Math.min(answer.length(), i + step)), null, null));
        }
        deltas.add(new ChatDelta(null, promptTokens, completionTokens));
        return Flux.fromIterable(deltas);
    }

    @Override
    public String complete(CompleteRequest request) {
        completeRequests.add(request);
        return completeResult;
    }
}
