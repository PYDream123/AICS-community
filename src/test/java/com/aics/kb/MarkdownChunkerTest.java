package com.aics.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化切片测试（技术设计 §9.1）：标题层级、短段合并、超长二次切分、
 * 表格原子性、代码块不被破坏、空标题丢弃、heading_path 拼接。
 */
class MarkdownChunkerTest {

    private final MarkdownChunker chunker = new MarkdownChunker(800, 120, 80, 3);

    private static String text(int repeat) {
        return "这是一段用于验证切片的正文内容，需要足够长以便通过最小长度阈值。".repeat(repeat);
    }

    @Test
    @DisplayName("按标题层级切分，且文档标题（首个 H1）不进 headingPath")
    void splitsByHeadingAndExcludesDocTitle() {
        String md = """
                # 售后服务政策 v3

                ## 2. 退货与换货

                ### 2.1 七天无理由适用条件

                %s

                ### 2.3 退货运费承担

                %s
                """.formatted(text(4), text(4));

        List<MarkdownChunker.Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).headingPath()).isEqualTo("2. 退货与换货 > 2.1 七天无理由适用条件");
        assertThat(chunks.get(0).headingText()).isEqualTo("2.1 七天无理由适用条件");
        assertThat(chunks.get(1).headingPath()).isEqualTo("2. 退货与换货 > 2.3 退货运费承担");
        assertThat(chunks.get(1).headingLevel()).isEqualTo(3);
        assertThat(chunks.get(0).index()).isZero();
        assertThat(chunks.get(1).index()).isEqualTo(1);
    }

    @Test
    @DisplayName("短段与下一个兄弟节点合并，合并后保留被合并章节的标题行")
    void mergesShortSiblings() {
        String md = """
                ## 1. 会员等级

                ### 1.1 等级划分

                会员分为三级。

                ### 1.2 等级权益

                银卡九折，金卡八折。
                """;

        List<MarkdownChunker.Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingText()).isEqualTo("1.1 等级划分");
        assertThat(chunks.get(0).headingPath()).isEqualTo("1. 会员等级 > 1.1 等级划分");
        assertThat(chunks.get(0).content()).contains("1.2 等级权益").contains("金卡八折");
    }

    @Test
    @DisplayName("不同父节点的短段不合并（否则引用会指向错误章节）")
    void doesNotMergeAcrossParents() {
        String md = """
                ## 1. 第一节

                ### 1.1 短标题

                短内容。

                ## 2. 第二节

                ### 2.1 另一个短标题

                另一段短内容。
                """;

        List<MarkdownChunker.Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).headingPath()).isEqualTo("1. 第一节 > 1.1 短标题");
        assertThat(chunks.get(1).headingPath()).isEqualTo("2. 第二节 > 2.1 另一个短标题");
    }

    @Test
    @DisplayName("超长章节按空行分段聚合，窗口长度不超过上限")
    void splitsLongSection() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            body.append(text(2)).append("\n\n");
        }
        String md = "## 3. 长章节\n\n" + body;

        List<MarkdownChunker.Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.content().length()).isLessThanOrEqualTo(900);
            assertThat(c.headingPath()).isEqualTo("3. 长章节");
        });
        assertThat(chunks).allMatch(c -> !c.oversized());
    }

    @Test
    @DisplayName("表格是原子单元，不会被从中间切断")
    void keepsTableAtomic() {
        StringBuilder table = new StringBuilder("| 项目 | 价格 |\n|---|---|\n");
        for (int i = 0; i < 60; i++) {
            table.append("| 维修项目").append(i).append(" | ").append(100 + i).append(" 元 |\n");
        }
        String md = "## 2.2 保外维修收费标准\n\n" + text(2) + "\n\n" + table + "\n" + text(2);

        List<MarkdownChunker.Chunk> chunks = chunker.chunk(md);

        long tableChunks = chunks.stream().filter(c -> c.content().contains("| 项目 | 价格 |")).count();
        assertThat(tableChunks).isEqualTo(1);
        assertThat(chunks).anySatisfy(c -> {
            assertThat(c.content()).contains("| 维修项目0 |");
            assertThat(c.content()).contains("| 维修项目47 |");
        });
        assertThat(chunks).anySatisfy(c -> assertThat(c.oversized()).isTrue());
    }

    @Test
    @DisplayName("代码块内的 # 不会被当成标题，围栏内容不被拆散")
    void keepsCodeFenceAtomic() {
        String md = """
                ## 4. 配置示例

                如下是配置：

                ```yaml
                # 这不是标题
                aics:
                  retrieval:
                    top-k: 6
                ```

                %s
                """.formatted(text(3));

        List<MarkdownChunker.Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingText()).isEqualTo("4. 配置示例");
        assertThat(chunks.get(0).content()).contains("# 这不是标题").contains("top-k: 6");
    }

    @Test
    @DisplayName("纯标题节点（body 为空）被丢弃")
    void dropsEmptySections() {
        String md = """
                ## 1. 空标题

                ## 2. 有内容

                %s
                """.formatted(text(3));

        List<MarkdownChunker.Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingText()).isEqualTo("2. 有内容");
    }

    @Test
    @DisplayName("H4 及以下视为正文，不产生新的切片")
    void treatsDeepHeadingsAsBody() {
        String md = """
                ## 1. 正文层级

                #### 1.1.1 这是一个很深的标题

                %s
                """.formatted(text(3));

        List<MarkdownChunker.Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingText()).isEqualTo("1. 正文层级");
        assertThat(chunks.get(0).content()).contains("这是一个很深的标题");
    }

    @Test
    @DisplayName("无标题文档：整篇作为一个切片，headingPath 为空")
    void handlesPlainText() {
        String md = text(4);

        List<MarkdownChunker.Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingPath()).isEmpty();
        assertThat(chunks.get(0).headingText()).isEmpty();
    }

    @Test
    @DisplayName("CRLF 与行尾空白被归一化，不影响切分结果")
    void normalizesLineEndings() {
        String md = "# 标题\r\n\r\n## 1. 章节\r\n\r\n" + text(3) + "   \r\n";

        List<MarkdownChunker.Chunk> chunks = chunker.chunk(md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingPath()).isEqualTo("1. 章节");
        assertThat(chunks.get(0).content()).doesNotContain("\r");
    }

    @Test
    @DisplayName("charStart/charEnd 能定位回原文")
    void tracksOffsets() {
        String body = text(3);
        String md = "## 1. 章节\n\n" + body;

        MarkdownChunker.Chunk chunk = chunker.chunk(md).getFirst();

        assertThat(md.substring(chunk.charStart(), chunk.charEnd())).isEqualTo(body.strip());
    }
}
