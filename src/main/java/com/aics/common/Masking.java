package com.aics.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 落库前脱敏（非功能要求「工具调用参数日志脱敏」）。
 *
 * <p>MVP 只覆盖两类最敏感的信息：手机号与订单号。订单号虽非严格隐私，
 * 但配合手机号可定位到具体用户，日志里统一打码。
 */
public final class Masking {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern ORDER_NO = Pattern.compile("(?i)\\bSO(\\d{6})(\\d{2})(\\d{4})\\b");
    private static final Pattern CONTACT_KEY = Pattern.compile("(?i).*(contact|phone|mobile|tel).*");
    private static final Pattern ORDER_KEY = Pattern.compile("(?i).*(order|waybill).*");

    private Masking() {
    }

    public static String maskText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String masked = PHONE.matcher(raw).replaceAll("$1****$2");
        return ORDER_NO.matcher(masked).replaceAll("SO$1**$3");
    }

    /** 按字段名判断敏感度，再对值做打码；未识别的字段原样保留（短文本本身不敏感）。 */
    public static Map<String, Object> maskArgs(Map<String, Object> args) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (args == null) {
            return out;
        }
        args.forEach((k, v) -> {
            if (v instanceof String s) {
                if (CONTACT_KEY.matcher(k).matches()) {
                    out.put(k, maskText(s));
                } else if (ORDER_KEY.matcher(k).matches()) {
                    out.put(k, ORDER_NO.matcher(s).replaceAll("SO$1**$3"));
                } else {
                    out.put(k, maskText(s));
                }
            } else {
                out.put(k, v);
            }
        });
        return out;
    }
}
