package com.aics.tool;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

/** tool_invocation：幂等键唯一约束是「同一会话重复提交不产生重复工单」的落地方式。 */
@Repository
public class ToolInvocationRepository {

    private final JdbcClient jdbc;

    public ToolInvocationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Stored(
            String status,
            String errorCode,
            String resultJson,
            int latencyMs,
            boolean replayed) {
    }

    /** 预留幂等键；返回 false 表示已有同键记录（重复提交）。 */
    public boolean reserve(long sessionId, String traceId, String toolName, String idemKey,
                           String argsMaskedJson) {
        int rows = jdbc.sql("""
                        INSERT INTO tool_invocation (session_id, trace_id, tool_name, idem_key, args_masked, status)
                        VALUES (:sid, :trace, :tool, :key, CAST(:args AS jsonb), 'IN_PROGRESS')
                        ON CONFLICT (session_id, tool_name, idem_key) DO NOTHING
                        """)
                .param("sid", sessionId)
                .param("trace", traceId)
                .param("tool", toolName)
                .param("key", idemKey)
                .param("args", argsMaskedJson)
                .update();
        return rows > 0;
    }

    public Optional<Stored> findByKey(long sessionId, String toolName, String idemKey) {
        return jdbc.sql("""
                        SELECT status, error_code, result::text AS result, latency_ms, replayed
                        FROM tool_invocation
                        WHERE session_id = :sid AND tool_name = :tool AND idem_key = :key
                        """)
                .param("sid", sessionId)
                .param("tool", toolName)
                .param("key", idemKey)
                .query((rs, rowNum) -> new Stored(
                        rs.getString("status"),
                        rs.getString("error_code"),
                        rs.getString("result"),
                        rs.getInt("latency_ms"),
                        rs.getBoolean("replayed")))
                .optional();
    }

    public void complete(long sessionId, String toolName, String idemKey, String status, String errorCode,
                         String resultJson, int latencyMs, boolean replayed) {
        jdbc.sql("""
                        UPDATE tool_invocation
                        SET status = :status, error_code = :code, result = CAST(:result AS jsonb),
                            latency_ms = :ms, replayed = :replayed
                        WHERE session_id = :sid AND tool_name = :tool AND idem_key = :key
                        """)
                .param("status", status)
                .param("code", errorCode)
                .param("result", resultJson)
                .param("ms", latencyMs)
                .param("replayed", replayed)
                .param("sid", sessionId)
                .param("tool", toolName)
                .param("key", idemKey)
                .update();
    }

    public Map<String, Object> summary() {
        return jdbc.sql("SELECT count(*) AS total FROM tool_invocation")
                .query()
                .singleRow();
    }
}
