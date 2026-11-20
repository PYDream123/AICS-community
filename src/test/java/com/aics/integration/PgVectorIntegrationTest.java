package com.aics.integration;

import com.aics.handoff.HandoffService;
import com.aics.kb.KbChunkRepository;
import com.aics.kb.KbDocumentRepository;
import com.aics.kb.MarkdownChunker;
import com.aics.tool.ToolInvocationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pgvector 集成测试（技术设计 §9.2）。
 *
 * <p>{@code disabledWithoutDocker = true}：本机没有 Docker 时整类跳过，
 * 交付说明中必须写明「集成测试未执行」而不是默认通过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class PgVectorIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("aics")
            .withUsername("aics")
            .withPassword("aics");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KbDocumentRepository documents;

    @Autowired
    KbChunkRepository chunks;

    @Autowired
    ToolInvocationRepository invocations;

    @Autowired
    HandoffService handoff;

    @Test
    @DisplayName("Flyway 迁移可执行，核心表与向量扩展就绪")
    void migrationsApplied() {
        Integer vectors = jdbc.sql("SELECT count(*) FROM pg_extension WHERE extname = 'vector'")
                .query(Integer.class).single();
        assertThat(vectors).isEqualTo(1);

        for (String table : List.of("kb_document", "kb_chunk", "chat_session", "chat_message",
                "qa_trace", "trace_retrieval", "tool_invocation", "handoff_ticket",
                "mock_order", "mock_logistics_event", "support_ticket")) {
            Integer exists = jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_name = :t")
                    .param("t", table)
                    .query(Integer.class)
                    .single();
            assertThat(exists).as("表 " + table).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("模拟业务数据覆盖正常、异常、边界三类")
    void mockDataCoversAllCases() {
        assertThat(count("SELECT count(*) FROM mock_order WHERE status = 'SIGNED'")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM mock_order WHERE status = 'SHIPPED'")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM mock_order WHERE status = 'CANCELLED'")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM mock_order WHERE status = 'PAID'")).isEqualTo(3);
        // 故意不存在的号段
        assertThat(count("SELECT count(*) FROM mock_order WHERE order_no = 'SO202611209901'")).isZero();
        assertThat(count("SELECT count(*) FROM mock_logistics_event WHERE order_no = 'SO202611180032'"))
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("向量检索按余弦相似度排序，top-k 顺序正确")
    void vectorSearchOrder() {
        long docId = documents.insert("向量测试.md", 128, "sha-vector-" + System.nanoTime());
        float[] near = unit(0);
        float[] far = unit(1);
        chunks.insert(docId, chunk(0, "接近", "命中内容"), "sha-near-" + System.nanoTime(), near);
        chunks.insert(docId, chunk(1, "远离", "无关内容"), "sha-far-" + System.nanoTime(), far);
        documents.markReady(docId, 2);

        List<KbChunkRepository.SearchRow> rows = chunks.search(com.aics.common.Vectors.toLiteral(near), 5);

        assertThat(rows).isNotEmpty();
        assertThat(rows.getFirst().headingText()).isEqualTo("接近");
        assertThat(rows.getFirst().score()).isGreaterThan(0.99);
        if (rows.size() > 1) {
            assertThat(rows.get(0).score()).isGreaterThanOrEqualTo(rows.get(1).score());
        }

        documents.delete(docId);
    }

    @Test
    @DisplayName("删除文档时级联清除切片与向量")
    void deletesChunksCascade() {
        long docId = documents.insert("级联测试.md", 64, "sha-cascade-" + System.nanoTime());
        chunks.insert(docId, chunk(0, "章节", "内容"), "sha-c1-" + System.nanoTime(), unit(0));
        chunks.insert(docId, chunk(1, "章节2", "内容2"), "sha-c2-" + System.nanoTime(), unit(2));
        documents.markReady(docId, 2);
        assertThat(chunks.countByDoc(docId)).isEqualTo(2);

        documents.delete(docId);

        assertThat(chunks.countByDoc(docId)).isZero();
    }

    @Test
    @DisplayName("工具幂等：同一会话同参数的第二次调用被唯一约束拦下")
    void toolInvocationUnique() {
        long sessionId = 9_000_001L;
        String key = "k-" + System.nanoTime();

        boolean first = invocations.reserve(sessionId, "trace-1", "create_ticket", key, "{\"a\":1}");
        boolean second = invocations.reserve(sessionId, "trace-2", "create_ticket", key, "{\"a\":1}");

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(invocations.findByKey(sessionId, "create_ticket", key))
                .isPresent()
                .get()
                .extracting(ToolInvocationRepository.Stored::status)
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("同一会话同一原因只保留一条待接管记录，避免刷屏队列")
    void handoffKeepsSingleOpenTicket() {
        long sessionId = createSession();

        handoff.suggest(sessionId, HandoffService.ReasonCode.LOW_CONFIDENCE, "问题甲");
        handoff.suggest(sessionId, HandoffService.ReasonCode.LOW_CONFIDENCE, "问题甲（重复追问）");

        Integer tickets = jdbc.sql("SELECT count(*) FROM handoff_ticket WHERE session_id = :sid AND status <> 'HANDLED'")
                .param("sid", sessionId)
                .query(Integer.class)
                .single();
        assertThat(tickets).isEqualTo(1);

        handoff.request(sessionId, HandoffService.ReasonCode.LOW_CONFIDENCE, "问题甲");
        assertThat(jdbc.sql("SELECT status FROM handoff_ticket WHERE session_id = :sid")
                .param("sid", sessionId)
                .query(String.class).single()).isEqualTo("WAITING");
    }

    private long createSession() {
        return jdbc.sql("INSERT INTO chat_session (title) VALUES ('集成测试') RETURNING id")
                .query(Long.class)
                .single();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private static MarkdownChunker.Chunk chunk(int index, String heading, String content) {
        return new MarkdownChunker.Chunk(index, "1. 测试 > " + heading, heading, 2, content, 0, content.length(), false);
    }

    /** 1024 维单位向量：第 pos 位为 1，其余为 0。 */
    private static float[] unit(int pos) {
        float[] v = new float[1024];
        v[pos] = 1.0f;
        return v;
    }
}
