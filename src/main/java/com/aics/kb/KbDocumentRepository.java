package com.aics.kb;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class KbDocumentRepository {

    private final JdbcClient jdbc;

    public KbDocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record KbDocument(
            long id,
            String filename,
            long sizeBytes,
            String contentSha256,
            String status,
            String errorMsg,
            int chunkCount,
            Instant uploadedAt,
            Instant processedAt) {
    }

    public long insert(String filename, long sizeBytes, String sha256) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO kb_document (filename, size_bytes, content_sha256, status)
                        VALUES (:filename, :size, :sha, 'PENDING')
                        """)
                .param("filename", filename)
                .param("size", sizeBytes)
                .param("sha", sha256)
                .update(keyHolder, "id");
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("文档落库失败：未返回主键");
        }
        return key.longValue();
    }

    public Optional<KbDocument> findById(long id) {
        return jdbc.sql("SELECT * FROM kb_document WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public List<KbDocument> findBySha256(String sha256) {
        return jdbc.sql("SELECT * FROM kb_document WHERE content_sha256 = :sha ORDER BY id DESC")
                .param("sha", sha256)
                .query(this::map)
                .list();
    }

    public List<KbDocument> findAll() {
        return jdbc.sql("SELECT * FROM kb_document ORDER BY uploaded_at DESC, id DESC")
                .query(this::map)
                .list();
    }

    public void markProcessing(long id) {
        jdbc.sql("UPDATE kb_document SET status = 'PROCESSING', error_msg = NULL WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void markReady(long id, int chunkCount) {
        jdbc.sql("""
                        UPDATE kb_document
                        SET status = 'READY', error_msg = NULL, chunk_count = :n, processed_at = now()
                        WHERE id = :id
                        """)
                .param("n", chunkCount)
                .param("id", id)
                .update();
    }

    public void markFailed(long id, String readableReason) {
        jdbc.sql("""
                        UPDATE kb_document
                        SET status = 'FAILED', error_msg = :msg, processed_at = now()
                        WHERE id = :id
                        """)
                .param("msg", readableReason)
                .param("id", id)
                .update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM kb_document WHERE id = :id").param("id", id).update();
    }

    public int countReady() {
        return jdbc.sql("SELECT count(*) FROM kb_document WHERE status = 'READY'")
                .query(Integer.class)
                .single();
    }

    private KbDocument map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp uploaded = rs.getTimestamp("uploaded_at");
        Timestamp processed = rs.getTimestamp("processed_at");
        return new KbDocument(
                rs.getLong("id"),
                rs.getString("filename"),
                rs.getLong("size_bytes"),
                rs.getString("content_sha256"),
                rs.getString("status"),
                rs.getString("error_msg"),
                rs.getInt("chunk_count"),
                uploaded == null ? null : uploaded.toInstant(),
                processed == null ? null : processed.toInstant());
    }
}
