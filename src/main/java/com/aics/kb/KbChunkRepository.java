package com.aics.kb;

import com.aics.common.Vectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class KbChunkRepository {

    private final JdbcClient jdbc;

    public KbChunkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ChunkRow(
            long id,
            long docId,
            int chunkIndex,
            String headingPath,
            String headingText,
            int headingLevel,
            String content,
            int tokenCount,
            boolean oversized) {
    }

    public record SearchRow(
            long chunkId,
            long docId,
            String filename,
            String headingPath,
            String headingText,
            String content,
            double score) {
    }

    public void insert(long docId, MarkdownChunker.Chunk chunk, String contentSha256, float[] embedding) {
        jdbc.sql("""
                        INSERT INTO kb_chunk (doc_id, chunk_index, heading_path, heading_text, heading_level,
                                              content, char_start, char_end, token_count, oversized,
                                              content_sha256, embedding)
                        VALUES (:docId, :idx, :path, :text, :level, :content, :start, :end, :tokens,
                                :oversized, :sha, CAST(:embedding AS vector))
                        """)
                .param("docId", docId)
                .param("idx", chunk.index())
                .param("path", truncate(chunk.headingPath(), 512))
                .param("text", truncate(chunk.headingText(), 255))
                .param("level", (short) chunk.headingLevel())
                .param("content", chunk.content())
                .param("start", chunk.charStart())
                .param("end", chunk.charEnd())
                .param("tokens", MarkdownChunker.tokenCount(chunk.content()))
                .param("oversized", chunk.oversized())
                .param("sha", contentSha256)
                .param("embedding", Vectors.toLiteral(embedding))
                .update();
    }

    /** Embedding 缓存：内容 sha 相同则复用已有向量，避免重建文档时重复计费。 */
    public Optional<float[]> findEmbeddingBySha(String contentSha256) {
        return jdbc.sql("SELECT embedding::text AS embedding FROM kb_chunk WHERE content_sha256 = :sha LIMIT 1")
                .param("sha", contentSha256)
                .query((rs, rowNum) -> rs.getString("embedding"))
                .optional()
                .map(Vectors::fromLiteral);
    }

    public List<ChunkRow> findByDoc(long docId) {
        return jdbc.sql("""
                        SELECT id, doc_id, chunk_index, heading_path, heading_text, heading_level,
                               content, token_count, oversized
                        FROM kb_chunk WHERE doc_id = :docId ORDER BY chunk_index
                        """)
                .param("docId", docId)
                .query((rs, rowNum) -> new ChunkRow(
                        rs.getLong("id"),
                        rs.getLong("doc_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("heading_path"),
                        rs.getString("heading_text"),
                        rs.getInt("heading_level"),
                        rs.getString("content"),
                        rs.getInt("token_count"),
                        rs.getBoolean("oversized")))
                .list();
    }

    public int deleteByDoc(long docId) {
        return jdbc.sql("DELETE FROM kb_chunk WHERE doc_id = :docId").param("docId", docId).update();
    }

    public int countByDoc(long docId) {
        return jdbc.sql("SELECT count(*) FROM kb_chunk WHERE doc_id = :docId")
                .param("docId", docId)
                .query(Integer.class)
                .single();
    }

    /**
     * 余弦相似度检索：pgvector 的 {@code <=>} 返回 cosine distance，score = 1 - distance。
     *
     * @param limit 候选数量，调用方按 topK * multiplier 取，再在应用层做阈值与单文档限流
     */
    public List<SearchRow> search(String vectorLiteral, int limit) {
        return jdbc.sql("""
                        SELECT c.id AS chunk_id, c.doc_id, d.filename, c.heading_path, c.heading_text,
                               c.content, 1 - (c.embedding <=> CAST(:vec AS vector)) AS score
                        FROM kb_chunk c
                        JOIN kb_document d ON d.id = c.doc_id
                        WHERE d.status = 'READY'
                        ORDER BY c.embedding <=> CAST(:vec AS vector)
                        LIMIT :limit
                        """)
                .param("vec", vectorLiteral)
                .param("limit", limit)
                .query((rs, rowNum) -> new SearchRow(
                        rs.getLong("chunk_id"),
                        rs.getLong("doc_id"),
                        rs.getString("filename"),
                        rs.getString("heading_path"),
                        rs.getString("heading_text"),
                        rs.getString("content"),
                        rs.getDouble("score")))
                .list();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
