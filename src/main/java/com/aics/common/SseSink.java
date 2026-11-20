package com.aics.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * SSE 写出的唯一通道（技术设计 §3.4.3）。
 *
 * <ul>
 *   <li>并发写：全部 send 收敛到一把锁，Reactor 线程与 Tomcat 线程都从这里出去；</li>
 *   <li>客户端断开：写失败即标记 closed，后续写直接丢弃，不抛给上层；</li>
 *   <li>长连接：按 heartbeat 间隔发注释行，防止中间层掐连接。</li>
 * </ul>
 */
public class SseSink implements ChatSink {

    private static final Logger log = LoggerFactory.getLogger(SseSink.class);

    private final SseEmitter emitter;
    private final long heartbeatMs;
    private final ScheduledExecutorService scheduler;
    private final Object lock = new Object();
    private final java.util.List<Runnable> closeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private volatile boolean closed;
    private ScheduledFuture<?> heartbeat;

    public SseSink(SseEmitter emitter, long heartbeatMs, ScheduledExecutorService scheduler) {
        this.emitter = emitter;
        this.heartbeatMs = heartbeatMs;
        this.scheduler = scheduler;
        startHeartbeat();
    }

    @Override
    public void send(String event, Object payload) {
        if (closed) {
            return;
        }
        String json = payload == null ? "{}" : Json.writeOrNull(payload);
        boolean closedNow = false;
        synchronized (lock) {
            if (closed) {
                return;
            }
            try {
                // TEXT_PLAIN 走预编码分支：字符串原样写出，不会被 JSON 转换器二次转义
                emitter.send(SseEmitter.event().name(event).data(json, MediaType.TEXT_PLAIN));
            } catch (IOException | IllegalStateException e) {
                closed = true;
                closedNow = true;
                cancelHeartbeat();
                log.debug("SSE 写出失败，客户端可能已断开: {}", e.toString());
            }
        }
        if (closedNow) {
            runCloseListeners();
        }
    }

    public void sendData(Object payload) {
        send("message", payload);
    }

    public void comment(String text) {
        if (closed) {
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment(text));
            } catch (IOException | IllegalStateException e) {
                closed = true;
                cancelHeartbeat();
            }
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void whenClosed(Runnable action) {
        if (closed) {
            action.run();
            return;
        }
        closeListeners.add(action);
        if (closed) {
            runCloseListeners();
        }
    }

    private void runCloseListeners() {
        for (Runnable r : closeListeners) {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.debug("关闭回调异常: {}", e.toString());
            }
        }
        closeListeners.clear();
    }

    /** 客户端断开时由容器回调：只做「标记关闭 + 触发取消回调」，不再碰 emitter。 */
    public void abort() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            cancelHeartbeat();
        }
        runCloseListeners();
    }

    public void complete() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            cancelHeartbeat();
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                log.debug("SSE complete 异常: {}", e.toString());
            }
        }
        runCloseListeners();
    }

    public void error(Throwable error) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            cancelHeartbeat();
            try {
                emitter.completeWithError(error);
            } catch (RuntimeException e) {
                log.debug("SSE completeWithError 异常: {}", e.toString());
            }
        }
        runCloseListeners();
    }

    public static Map<String, Object> event(String key, Object value) {
        return Map.of(key, value);
    }

    private void startHeartbeat() {
        if (heartbeatMs <= 0) {
            return;
        }
        heartbeat = scheduler.scheduleAtFixedRate(() -> {
            if (!closed) {
                comment("ping");
            }
        }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> hb = heartbeat;
        if (hb != null) {
            hb.cancel(false);
            heartbeat = null;
        }
    }
}
