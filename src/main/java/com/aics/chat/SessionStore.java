package com.aics.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 会话与消息的持久化边界；实现类用 JDBC，测试用内存替身。 */
public interface SessionStore {

    record Session(long id, String title, String status, String summary, int summaryUptoSeq,
                   Instant createdAt, Instant updatedAt) {
    }

    record Message(long id, int seq, String role, String content, String refsJson, String toolCallsJson,
                   String traceId, Instant createdAt) {
    }

    long createSession(String title);

    Optional<Session> findSession(long id);

    List<Session> listSessions(int limit);

    List<Message> messages(long sessionId);

    int nextSeq(long sessionId);

    /** @return 落库后的 message id */
    long appendMessage(long sessionId, int seq, String role, String content, String refsJson,
                       String toolCallsJson, String traceId);

    void updateSummary(long sessionId, String summary, int uptoSeq);

    void updateStatus(long sessionId, String status);

    void touch(long sessionId, String title);
}
