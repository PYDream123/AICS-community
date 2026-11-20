package com.aics.chat;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcSessionStore implements SessionStore {

    private final JdbcClient jdbc;

    public JdbcSessionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long createSession(String title) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO chat_session (title) VALUES (:title)")
                .param("title", title == null || title.isBlank() ? "新会话" : truncate(title, 120))
                .update(keyHolder, "id");
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建会话失败：未返回主键");
        }
        return key.longValue();
    }

    @Override
    public Optional<Session> findSession(long id) {
        return jdbc.sql("SELECT * FROM chat_session WHERE id = :id")
                .param("id", id)
                .query(this::mapSession)
                .optional();
    }

    @Override
    public List<Session> listSessions(int limit) {
        return jdbc.sql("SELECT * FROM chat_session ORDER BY updated_at DESC, id DESC LIMIT :limit")
                .param("limit", limit)
                .query(this::mapSession)
                .list();
    }

    @Override
    public List<Message> messages(long sessionId) {
        return jdbc.sql("SELECT * FROM chat_message WHERE session_id = :sid ORDER BY seq")
                .param("sid", sessionId)
                .query(this::mapMessage)
                .list();
    }

    @Override
    public int nextSeq(long sessionId) {
        Integer max = jdbc.sql("SELECT coalesce(max(seq), 0) FROM chat_message WHERE session_id = :sid")
                .param("sid", sessionId)
                .query(Integer.class)
                .single();
        return (max == null ? 0 : max) + 1;
    }

    @Override
    public long appendMessage(long sessionId, int seq, String role, String content, String refsJson,
                              String toolCallsJson, String traceId) {
        long id;
        try {
            id = insert(sessionId, seq, role, content, refsJson, toolCallsJson, traceId);
        } catch (DuplicateKeyException e) {
            // 同一会话并发写入：退让一个序号重试一次
            id = insert(sessionId, nextSeq(sessionId), role, content, refsJson, toolCallsJson, traceId);
        }
        jdbc.sql("UPDATE chat_session SET updated_at = now() WHERE id = :sid").param("sid", sessionId).update();
        return id;
    }

    private long insert(long sessionId, int seq, String role, String content, String refsJson,
                        String toolCallsJson, String traceId) {
        return jdbc.sql("""
                        INSERT INTO chat_message (session_id, seq, role, content, refs, tool_calls, trace_id)
                        VALUES (:sid, :seq, :role, :content,
                                CAST(:refs AS jsonb), CAST(:tools AS jsonb), :trace)
                        RETURNING id
                        """)
                .param("sid", sessionId)
                .param("seq", seq)
                .param("role", role)
                .param("content", content)
                .param("refs", refsJson)
                .param("tools", toolCallsJson)
                .param("trace", traceId)
                .query(Long.class)
                .single();
    }

    @Override
    public void updateSummary(long sessionId, String summary, int uptoSeq) {
        jdbc.sql("""
                        UPDATE chat_session
                        SET summary = :summary, summary_upto_seq = :upto, updated_at = now()
                        WHERE id = :sid
                        """)
                .param("summary", summary)
                .param("upto", uptoSeq)
                .param("sid", sessionId)
                .update();
    }

    @Override
    public void updateStatus(long sessionId, String status) {
        jdbc.sql("UPDATE chat_session SET status = :status, updated_at = now() WHERE id = :sid")
                .param("status", status)
                .param("sid", sessionId)
                .update();
    }

    @Override
    public void touch(long sessionId, String title) {
        if (title == null || title.isBlank()) {
            jdbc.sql("UPDATE chat_session SET updated_at = now() WHERE id = :sid")
                    .param("sid", sessionId)
                    .update();
            return;
        }
        jdbc.sql("UPDATE chat_session SET title = :title, updated_at = now() WHERE id = :sid")
                .param("title", truncate(title, 120))
                .param("sid", sessionId)
                .update();
    }

    private Session mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new Session(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getString("summary"),
                rs.getInt("summary_upto_seq"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private Message mapMessage(ResultSet rs, int rowNum) throws SQLException {
        return new Message(
                rs.getLong("id"),
                rs.getInt("seq"),
                rs.getString("role"),
                rs.getString("content"),
                rs.getString("refs"),
                rs.getString("tool_calls"),
                rs.getString("trace_id"),
                toInstant(rs.getTimestamp("created_at")));
    }

    private static java.time.Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
