package com.aics.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** L2 兜底识别：模型自己说「不知道」时要能被系统捕捉到。 */
class RefusalDetectorTest {

    @Test
    @DisplayName("识别常见的拒答表述")
    void detectsRefusalPhrases() {
        assertThat(RefusalDetector.looksRefused("抱歉，这个问题暂无相关资料 [1]。")).isTrue();
        assertThat(RefusalDetector.looksRefused("资料中没有提及该问题。")).isTrue();
        assertThat(RefusalDetector.looksRefused("我无法回答这个问题。")).isTrue();
    }

    @Test
    @DisplayName("正常回答不会被误判")
    void normalAnswerIsNotRefusal() {
        assertThat(RefusalDetector.looksRefused("整机保修 12 个月，主要部件 24 个月 [1]。")).isFalse();
    }

    @Test
    @DisplayName("只在开头判断，正文里出现「没有」不算拒答")
    void onlyChecksHead() {
        String answer = "整机保修 12 个月，主要部件 24 个月，具体以订单签收日期为准 [1]。".repeat(6)
                + "补充说明：资料中没有提及赠品保修，建议转人工确认。";
        assertThat(RefusalDetector.looksRefused(answer)).isFalse();
    }

    @Test
    @DisplayName("空回答不当作拒答")
    void blankIsNotRefusal() {
        assertThat(RefusalDetector.looksRefused("")).isFalse();
        assertThat(RefusalDetector.looksRefused(null)).isFalse();
    }
}
