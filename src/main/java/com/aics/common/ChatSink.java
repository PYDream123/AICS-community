package com.aics.common;

/**
 * 事件出口抽象。SSE 走 {@link SseSink}，评测/测试走 {@link CollectingSink}，
 * 编排逻辑不需要知道对面是浏览器还是命令行（技术设计 §3.9.3「进程内调用」）。
 */
public interface ChatSink {

    void send(String event, Object payload);

    boolean isClosed();

    /** 客户端断开时的回调：用于 dispose 掉模型订阅，避免继续计费（技术设计 §3.4.3）。 */
    default void whenClosed(Runnable action) {
    }

    ChatSink NOOP = new ChatSink() {
        @Override
        public void send(String event, Object payload) {
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    };
}
