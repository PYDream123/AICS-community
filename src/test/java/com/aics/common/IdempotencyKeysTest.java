package com.aics.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 幂等键：语义相同 ⇒ 键相同（技术设计 §3.5.4 / §9.1）。 */
class IdempotencyKeysTest {

    @Test
    @DisplayName("参数顺序不同但语义相同，键必须一致")
    void keyIsOrderIndependent() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("orderNo", "SO202611180032");
        a.put("channel", "app");

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("channel", "app");
        b.put("orderNo", "SO202611180032");

        assertThat(IdempotencyKeys.of(1L, "query_order", a))
                .isEqualTo(IdempotencyKeys.of(1L, "query_order", b));
    }

    @Test
    @DisplayName("数字统一格式：1 与 1.0 视为同一参数")
    void normalizesNumbers() {
        assertThat(IdempotencyKeys.of(1L, "t", Map.of("n", 1)))
                .isEqualTo(IdempotencyKeys.of(1L, "t", Map.of("n", 1.0)))
                .isEqualTo(IdempotencyKeys.of(1L, "t", Map.of("n", 1.00)));
    }

    @Test
    @DisplayName("会话、工具名或参数变化都会改变键")
    void keyChangesWithInput() {
        Map<String, Object> args = Map.of("orderNo", "SO202611180032");

        assertThat(IdempotencyKeys.of(1L, "query_order", args))
                .isNotEqualTo(IdempotencyKeys.of(2L, "query_order", args))
                .isNotEqualTo(IdempotencyKeys.of(1L, "create_ticket", args))
                .isNotEqualTo(IdempotencyKeys.of(1L, "query_order", Map.of("orderNo", "SO202610010011")));
    }

    @Test
    @DisplayName("canonicalJson 去掉空白并对键排序")
    void canonicalJsonIsStable() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("b", "x y");
        args.put("a", 1);
        assertThat(IdempotencyKeys.canonicalJson(args)).isEqualTo("{\"a\":1,\"b\":\"x y\"}");
    }

    @Test
    @DisplayName("sha256 输出 64 位十六进制")
    void sha256Format() {
        String hex = IdempotencyKeys.sha256Hex("abc");
        assertThat(hex).hasSize(64).matches("[0-9a-f]{64}");
    }
}
