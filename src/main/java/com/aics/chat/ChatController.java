package com.aics.chat;

import com.aics.common.AicsProperties;
import com.aics.common.AppException;
import com.aics.common.Json;
import com.aics.common.SseSink;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

/** 会话与对话接口（技术设计 §5.2）。 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final ChatOrchestrator orchestrator;
    private final SessionStore store;
    private final AicsProperties props;
    private final ThreadPoolTaskExecutor chatExecutor;
    private final ScheduledExecutorService heartbeatScheduler;

    public ChatController(ChatOrchestrator orchestrator, SessionStore store, AicsProperties props,
                          @Qualifier("chatExecutor") ThreadPoolTaskExecutor chatExecutor,
                          @Qualifier("sseHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
        this.orchestrator = orchestrator;
        this.store = store;
        this.props = props;
        this.chatExecutor = chatExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    public record ChatRequest(Long sessionId, String message) {
    }

    public record SessionView(long sessionId, String title, String status, String time, String preview) {
    }

    public record MessageView(long id, int seq, String role, String content, List<RefView> refs,
                              String traceId, String time) {
    }

    public record CreatedSession(long sessionId, String title) {
    }

    @PostMapping("/sessions")
    public CreatedSession createSession() {
        long id = store.createSession("新会话");
        return new CreatedSession(id, "新会话");
    }

    @GetMapping("/sessions")
    public List<SessionView> sessions() {
        return store.listSessions(30).stream()
                .map(s -> new SessionView(s.id(), s.title(), s.status(),
                        s.updatedAt() == null ? "" : TIME.format(s.updatedAt()), ""))
                .toList();
    }

    @GetMapping("/sessions/{id}/messages")
    public List<MessageView> messages(@PathVariable long id) {
        store.findSession(id).orElseThrow(() -> AppException.notFound("会话"));
        List<MessageView> out = new ArrayList<>();
        for (SessionStore.Message m : store.messages(id)) {
            out.add(new MessageView(m.id(), m.seq(), m.role(), m.content(), parseRefs(m.refsJson()),
                    m.traceId(), m.createdAt() == null ? "" : TIME.format(m.createdAt())));
        }
        return out;
    }

    /**
     * SSE 端点：立刻返回 emitter，编排在独立线程池里跑。
     *
     * <p>事件顺序：{@code meta → retrieval → (tool_call → tool_result)* → delta* → done}。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        String message = request.message() == null ? "" : request.message().strip();
        if (message.isEmpty()) {
            throw AppException.badRequest("消息不能为空");
        }
        long sessionId = request.sessionId() == null
                ? store.createSession(message.length() > 30 ? message.substring(0, 30) : message)
                : request.sessionId();
        store.findSession(sessionId).orElseThrow(() -> AppException.notFound("会话"));

        SseEmitter emitter = new SseEmitter(props.getSse().getTimeoutMs());
        SseSink sink = new SseSink(emitter, props.getSse().getHeartbeatMs(), heartbeatScheduler);

        emitter.onCompletion(sink::abort);
        emitter.onTimeout(() -> {
            sink.send("error", java.util.Map.of("code", "TIMEOUT", "message", "响应超时，请重新提问"));
            sink.abort();
            sink.complete();
        });
        emitter.onError(e -> {
            log.debug("SSE 连接异常: {}", e.toString());
            sink.abort();
        });

        chatExecutor.execute(() -> {
            try {
                orchestrator.handle(sessionId, message, sink);
            } catch (AppException e) {
                sink.send("error", java.util.Map.of("code", e.getCode(), "message", e.getMessage()));
            } catch (RuntimeException e) {
                log.error("对话处理失败 sessionId={}", sessionId, e);
                sink.send("error", java.util.Map.of("code", "INTERNAL_ERROR",
                        "message", "服务暂时不可用，请稍后重试"));
            } finally {
                sink.complete();
            }
        });
        return emitter;
    }

    private List<RefView> parseRefs(String refsJson) {
        if (refsJson == null || refsJson.isBlank()) {
            return List.of();
        }
        try {
            return Json.mapper().readValue(refsJson, new TypeReference<List<RefView>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
