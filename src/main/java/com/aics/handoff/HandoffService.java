package com.aics.handoff;

import com.aics.common.AppException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 人工接管（技术设计 §3.7.2）。
 *
 * <p>用状态字段统一产品文档里「会话进入待处理队列」与「系统建议转人工」两种表述：
 * <ul>
 *   <li>SUGGESTED：系统建议，用户还没点（拒答、工具连续失败）</li>
 *   <li>WAITING：用户已点击，进入待接管队列</li>
 *   <li>HANDLED：已处理</li>
 * </ul>
 */
@Service
public class HandoffService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneId.systemDefault());

    public enum ReasonCode {
        USER_REQUEST("用户主动转人工", false),
        LOW_CONFIDENCE("低置信转人工", true),
        TOOL_FAILURE("工具失败转人工", true),
        OUT_OF_SCOPE("超出范围", true);

        private final String text;
        private final boolean urgent;

        ReasonCode(String text, boolean urgent) {
            this.text = text;
            this.urgent = urgent;
        }

        public String text() {
            return text;
        }

        public boolean urgent() {
            return urgent;
        }

        public static ReasonCode of(String code) {
            if (code == null || code.isBlank()) {
                return USER_REQUEST;
            }
            try {
                return valueOf(code.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return USER_REQUEST;
            }
        }
    }

    public record QueueItem(long id, String session, String q, String reason, boolean urgent, String time,
                            String statusCode) {
    }

    public record Stats(long waiting, long todayTickets, long todaySessions, double handoffRate) {
    }

    private final JdbcClient jdbc;

    public HandoffService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 系统建议转人工：只记 SUGGESTED，不打扰队列；同会话同原因只保留一条。 */
    public void suggest(long sessionId, ReasonCode reason, String questionDigest) {
        insert(sessionId, reason, questionDigest, "SUGGESTED");
    }

    /** 用户主动点击：已有建议则升级为 WAITING，否则新建。 */
    public void request(long sessionId, ReasonCode reason, String questionDigest) {
        int updated = jdbc.sql("""
                        UPDATE handoff_ticket SET status = 'WAITING'
                        WHERE session_id = :sid AND status = 'SUGGESTED'
                        """)
                .param("sid", sessionId)
                .update();
        if (updated == 0) {
            insert(sessionId, reason, questionDigest, "WAITING");
        }
        jdbc.sql("UPDATE chat_session SET status = 'HANDOFF', updated_at = now() WHERE id = :sid")
                .param("sid", sessionId)
                .update();
    }

    private void insert(long sessionId, ReasonCode reason, String questionDigest, String status) {
        String digest = questionDigest == null || questionDigest.isBlank()
                ? "（未获取到问题摘要）" : truncate(questionDigest.strip(), 120);
        jdbc.sql("""
                        INSERT INTO handoff_ticket (session_id, reason_code, reason_text, question_digest, status)
                        VALUES (:sid, :code, :text, :digest, :status)
                        ON CONFLICT (session_id, reason_code) WHERE status <> 'HANDLED' DO NOTHING
                        """)
                .param("sid", sessionId)
                .param("code", reason.name())
                .param("text", reason.text())
                .param("digest", digest)
                .param("status", status)
                .update();
    }

    public List<QueueItem> queue() {
        return jdbc.sql("""
                        SELECT id, session_id, reason_code, reason_text, question_digest, status, created_at
                        FROM handoff_ticket
                        WHERE status = 'WAITING'
                        ORDER BY created_at DESC
                        LIMIT 50
                        """)
                .query((rs, rowNum) -> {
                    java.sql.Timestamp created = rs.getTimestamp("created_at");
                    Instant instant = created == null ? Instant.now() : created.toInstant();
                    String reasonCode = rs.getString("reason_code");
                    return new QueueItem(
                            rs.getLong("id"),
                            "C-" + DAY.format(instant) + "-" + String.format("%04d", rs.getLong("session_id")),
                            rs.getString("question_digest"),
                            rs.getString("reason_text"),
                            ReasonCode.of(reasonCode).urgent(),
                            TIME.format(instant),
                            rs.getString("status"));
                })
                .list();
    }

    public void markHandled(long ticketId) {
        int rows = jdbc.sql("UPDATE handoff_ticket SET status = 'HANDLED' WHERE id = :id")
                .param("id", ticketId)
                .update();
        if (rows == 0) {
            throw AppException.notFound("待接管记录");
        }
    }

    public Stats stats() {
        long waiting = jdbc.sql("SELECT count(*) FROM handoff_ticket WHERE status = 'WAITING'")
                .query(Long.class).single();
        long todayTickets = jdbc.sql("""
                        SELECT count(*) FROM handoff_ticket
                        WHERE created_at >= date_trunc('day', now()) AND status <> 'SUGGESTED'
                        """)
                .query(Long.class).single();
        long todaySessions = jdbc.sql("""
                        SELECT count(*) FROM chat_session WHERE created_at >= date_trunc('day', now())
                        """)
                .query(Long.class).single();
        double rate = todaySessions == 0 ? 0 : Math.round(todayTickets * 1000.0 / todaySessions) / 10.0;
        return new Stats(waiting, todayTickets, todaySessions, rate);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
