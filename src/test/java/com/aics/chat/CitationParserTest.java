package com.aics.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 引用解析：编号可校验、越界丢弃、无引用兜底（技术设计 §6.1 / §9.1）。 */
class CitationParserTest {

    @Test
    @DisplayName("解析 [1][3] 并去重排序")
    void parsesMultipleMarks() {
        assertThat(CitationParser.parse("结论一 [1]，结论二 [3]，再引 [1]。", 3))
                .containsExactly(1, 3);
    }

    @Test
    @DisplayName("越界编号被丢弃，避免引用准确率虚高")
    void dropsOutOfRange() {
        assertThat(CitationParser.parse("看起来引用了不存在的片段 [7]", 3)).isEmpty();
        assertThat(CitationParser.parse("[0] 与 [4] 都非法", 3)).isEmpty();
    }

    @Test
    @DisplayName("没有引用时返回空集合")
    void handlesNoCitation() {
        assertThat(CitationParser.parse("这是一句没有角标的回答", 3)).isEmpty();
        assertThat(CitationParser.parse(null, 3)).isEmpty();
        assertThat(CitationParser.parse("[1]", 0)).isEmpty();
    }

    @Test
    @DisplayName("识别「根据资料」类表述，供无引用时的事后校验")
    void detectsMaterialClaim() {
        assertThat(CitationParser.claimsBasedOnMaterial("根据资料显示，保修期为 12 个月。")).isTrue();
        assertThat(CitationParser.claimsBasedOnMaterial("整机保修 12 个月 [1]。")).isFalse();
    }

    @Test
    @DisplayName("同一编号多次出现只记一次")
    void deduplicates() {
        List<Integer> cited = CitationParser.parse("[2] [2] [2]", 2);
        assertThat(cited).containsExactly(2);
    }
}
