package com.aics.trace;

import com.aics.common.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Trace 落库：done 事件之后再写，不占首字预算；写失败只打日志。
 */
@Repository
public class JdbcTraceRecorder implements TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(JdbcTraceRecorder.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ThreadPoolTaskExecutor traceExecutor;

    public JdbcTraceRecorder(JdbcClient jdbc, TransactionTemplate tx,
                             @Qualifier("traceExecutor") ThreadPoolTaskExecutor traceExecutor) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.traceExecutor = traceExecutor;
    }

    @Override
    public void record(TraceRecord record, List<TraceRecord.RetrievalRow> retrievals) {
        try {
            traceExecutor.submit(() -> write(record, retrievals));
        } catch (RuntimeException e) {
            log.warn("Trace 任务提交失败（忽略）: {}", e.toString());
        }
    }

    private void write(TraceRecord record, List<TraceRecord.RetrievalRow> retrievals) {
        try {
            tx.executeWithoutResult(status -> {
                jdbc.sql("""
                                INSERT INTO qa_trace (id, session_id, message_id, turn, query_raw, query_used,
                                                      rewritten, top_score, threshold, refused, tool_used,
                                                      ttft_ms, total_ms, prompt_tokens, completion_tokens, stages)
                                VALUES (:id, :sid, CAST(:mid AS bigint), :turn, :raw, :used, :rewritten,
                                        :top, :threshold, :refused, :tool,
                                        CAST(:ttft AS integer), CAST(:total AS integer),
                                        CAST(:ptok AS integer), CAST(:ctok AS integer),
                                        CAST(:stages AS jsonb))
                                """)
                        .param("id", record.id())
                        .param("sid", record.sessionId())
                        .param("mid", record.messageId())
                        .param("turn", record.turn())
                        .param("raw", record.queryRaw())
                        .param("used", record.queryUsed())
                        .param("rewritten", record.rewritten())
                        .param("top", record.topScore())
                        .param("threshold", record.threshold())
                        .param("refused", record.refused())
                        .param("tool", record.toolUsed())
                        .param("ttft", record.ttftMs())
                        .param("total", record.totalMs())
                        .param("ptok", record.promptTokens())
                        .param("ctok", record.completionTokens())
                        .param("stages", Json.write(record.stages()))
                        .update();

                for (TraceRecord.RetrievalRow row : retrievals) {
                    jdbc.sql("""
                                    INSERT INTO trace_retrieval (trace_id, chunk_id, rank, score, cited)
                                    VALUES (:id, :chunk, :rank, :score, :cited)
                                    ON CONFLICT DO NOTHING
                                    """)
                            .param("id", record.id())
                            .param("chunk", row.chunkId())
                            .param("rank", row.rank())
                            .param("score", row.score())
                            .param("cited", row.cited())
                            .update();
                }
            });
        } catch (RuntimeException e) {
            log.warn("Trace 落库失败（不影响用户请求）traceId={}: {}", record.id(), e.toString());
        }
    }

    @Override
    public Optional<TraceView> find(String traceId) {
        return jdbc.sql("SELECT * FROM qa_trace WHERE id = :id")
                .param("id", traceId)
                .query((rs, rowNum) -> mapView(rs, details(traceId)))
                .optional();
    }

    @Override
    public List<TraceView> recent(int limit) {
        return jdbc.sql("SELECT * FROM qa_trace ORDER BY created_at DESC LIMIT :limit")
                .param("limit", limit)
                .query((rs, rowNum) -> mapView(rs, details(rs.getString("id"))))
                .list();
    }

    private List<RetrievalDetail> details(String traceId) {
        List<RetrievalDetail> out = new ArrayList<>();
        jdbc.sql("""
                        SELECT r.chunk_id, r.rank, r.score, r.cited, c.heading_text, d.filename
                        FROM trace_retrieval r
                        LEFT JOIN kb_chunk c ON c.id = r.chunk_id
                        LEFT JOIN kb_document d ON d.id = c.doc_id
                        WHERE r.trace_id = :id
                        ORDER BY r.rank
                        """)
                .param("id", traceId)
                .query((rs, rowNum) -> new RetrievalDetail(
                        rs.getLong("chunk_id"),
                        rs.getInt("rank"),
                        rs.getDouble("score"),
                        rs.getBoolean("cited"),
                        rs.getString("filename"),
                        rs.getString("heading_text")))
                .list()
                .forEach(out::add);
        return out;
    }

    @SuppressWarnings("unchecked")
    private TraceView mapView(ResultSet rs, List<RetrievalDetail> details) throws SQLException {
        List<List<Object>> stages = new ArrayList<>();
        String stagesJson = rs.getString("stages");
        if (stagesJson != null) {
            try {
                List<List<Object>> parsed = Json.mapper().readValue(stagesJson, List.class);
                stages = parsed;
            } catch (Exception e) {
                log.debug("stages 解析失败: {}", e.toString());
            }
        }
        Integer ttft = (Integer) rs.getObject("ttft_ms");
        Integer total = (Integer) rs.getObject("total_ms");
        Integer ptok = (Integer) rs.getObject("prompt_tokens");
        Integer ctok = (Integer) rs.getObject("completion_tokens");
        return new TraceView(
                rs.getString("id"),
                rs.getLong("session_id"),
                rs.getInt("turn"),
                rs.getString("query_raw"),
                rs.getString("query_used"),
                rs.getBoolean("rewritten"),
                rs.getDouble("top_score"),
                rs.getDouble("threshold"),
                rs.getBoolean("refused"),
                rs.getString("tool_used"),
                ttft,
                total,
                ptok,
                ctok,
                stages,
                details);
    }
}
