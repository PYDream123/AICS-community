package com.aics.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 落库前脱敏：手机号与订单号不得以明文进日志/数据库。 */
class MaskingTest {

    @Test
    @DisplayName("手机号中间四位打码")
    void masksPhone() {
        assertThat(Masking.maskText("联系我 13812345678")).isEqualTo("联系我 138****5678");
        assertThat(Masking.maskText("13812345678")).isEqualTo("138****5678");
    }

    @Test
    @DisplayName("订单号保留前后段，中间打码")
    void masksOrderNo() {
        assertThat(Masking.maskText("SO202611180032")).isEqualTo("SO202611**0032");
    }

    @Test
    @DisplayName("按字段名判断敏感度，非敏感字段原样保留")
    void masksArgsByKey() {
        Map<String, Object> masked = Masking.maskArgs(Map.of(
                "contact", "13812345678",
                "orderNo", "SO202611180032",
                "description", "门锁无法上锁"));

        assertThat(masked.get("contact")).isEqualTo("138****5678");
        assertThat(masked.get("orderNo")).isEqualTo("SO202611**0032");
        assertThat(masked.get("description")).isEqualTo("门锁无法上锁");
    }

    @Test
    @DisplayName("邮箱等未被规则覆盖的内容不会被误伤")
    void leavesEmailUntouched() {
        assertThat(Masking.maskText("support@example.com")).isEqualTo("support@example.com");
    }

    @Test
    @DisplayName("短号与超长数字不做误判")
    void ignoresNonPhoneDigits() {
        assertThat(Masking.maskText("订单 12345")).isEqualTo("订单 12345");
    }
}
