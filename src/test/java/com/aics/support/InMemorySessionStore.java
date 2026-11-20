package com.aics.support;

import com.aics.chat.SessionStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** 内存版会话存储：让编排与记忆的单元测试不依赖数据库。 */
public class InMemorySessionStore implements SessionStore {

    private final AtomicLong sessionIds = new AtomicLong(1);
    private final AtomicLong messageIds = new AtomicLong(1);
    private final Map<Long, Session> sessions = new LinkedHashMap<>();
    private final Map<Long, List<Message>> messages = new LinkedHashMap<>();

    @Override
    public long createSession(String title) {
        long id = sessionIds.getAndIncrement();
        Instant now = Instant.now();
        sessions.put(id, new Session(id, title == null ? "新会话" : title, "ACTIVE", null, 0, now, now));
        messages.put(id, new ArrayList<>());
        return id;
    }

    @Override
    public Optional<Session> findSession(long id) {
        return Optional.ofNullable(sessions.get(id));
    }

    @Override
    public List<Session> listSessions(int limit) {
        return sessions.values().stream()
                .sorted(Comparator.comparingLong(Session::id).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<Message> messages(long sessionId) {
        return List.copyOf(messages.getOrDefault(sessionId, List.of()));
    }

    @Override
    public int nextSeq(long sessionId) {
        List<Message> list = messages.getOrDefault(sessionId, List.of());
        return list.stream().mapToInt(Message::seq).max().orElse(0) + 1;
    }

    @Override
    public long appendMessage(long sessionId, int seq, String role, String content, String refsJson,
                              String toolCallsJson, String traceId) {
        long id = messageIds.getAndIncrement();
        messages.computeIfAbsent(sessionId, k -> new ArrayList<>())
                .add(new Message(id, seq, role, content, refsJson, toolCallsJson, traceId, Instant.now()));
        return id;
    }

    @Override
    public void updateSummary(long sessionId, String summary, int uptoSeq) {
        Session s = sessions.get(sessionId);
        if (s != null) {
            sessions.put(sessionId, new Session(s.id(), s.title(), s.status(), summary, uptoSeq,
                    s.createdAt(), Instant.now()));
        }
    }

    @Override
    public void updateStatus(long sessionId, String status) {
        Session s = sessions.get(sessionId);
        if (s != null) {
            sessions.put(sessionId, new Session(s.id(), s.title(), status, s.summary(), s.summaryUptoSeq(),
                    s.createdAt(), Instant.now()));
        }
    }

    @Override
    public void touch(long sessionId, String title) {
        Session s = sessions.get(sessionId);
        if (s != null) {
            sessions.put(sessionId, new Session(s.id(), title == null ? s.title() : title, s.status(),
                    s.summary(), s.summaryUptoSeq(), s.createdAt(), Instant.now()));
        }
    }
}
