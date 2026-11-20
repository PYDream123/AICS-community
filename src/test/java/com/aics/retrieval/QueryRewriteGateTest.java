package com.aics.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 改写门控：既要解决指代，又不能把首字预算吃光（技术设计 §3.3.1）。 */
class QueryRewriteGateTest {

    @Test
    @DisplayName("首轮对话不改写")
    void noHistoryNoRewrite() {
        assertThat(QueryRewriteGate.shouldRewrite("那过了保修期维修怎么收费", 0, false)).isFalse();
    }

    @Test
    @DisplayName("短问题在有历史时改写")
    void shortQuestionRewrites() {
        assertThat(QueryRewriteGate.shouldRewrite("保修期多久", 4, false)).isTrue();
    }

    @Test
    @DisplayName("命中指代/承接词时改写")
    void anaphoraTriggersRewrite() {
        assertThat(QueryRewriteGate.shouldRewrite("那过了保修期之后如果要维修，大概会怎么收费呢", 4, false))
                .isTrue();
        assertThat(QueryRewriteGate.shouldRewrite("它支持几天无理由退货，运费怎么算比较合理", 4, false))
                .isTrue();
    }

    @Test
    @DisplayName("独立长问题不改写，省掉一次模型往返")
    void standaloneQuestionSkips() {
        assertThat(QueryRewriteGate.shouldRewrite("请问七天无理由退货的退回运费由谁承担，质量问题如何处理", 4, false))
                .isFalse();
    }

    @Test
    @DisplayName("上一轮在等槽位时一律改写（用户可能只回了一个订单号）")
    void awaitingSlotAlwaysRewrites() {
        assertThat(QueryRewriteGate.shouldRewrite("我的订单什么时候能到", 6, true)).isTrue();
    }

    @Test
    @DisplayName("空问题不改写")
    void blankSkips() {
        assertThat(QueryRewriteGate.shouldRewrite("  ", 4, false)).isFalse();
        assertThat(QueryRewriteGate.shouldRewrite(null, 4, false)).isFalse();
    }
}
