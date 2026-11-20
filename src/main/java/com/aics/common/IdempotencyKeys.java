package com.aics.common;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等键：{@code sha256(sessionId | toolName | canonicalJson(args))}（技术设计 §3.5.4）。
 *
 * <p>关键在 canonicalJson：键字典序、无空白、数字统一格式，保证「语义相同 ⇒ 键相同」。
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    public static String of(long sessionId, String toolName, Map<String, Object> args) {
        String payload = sessionId + "|" + toolName + "|" + canonicalJson(args);
        return sha256Hex(payload);
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    public static String sha256Hex(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    public static String canonicalJson(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> quote(sb, s);
            case Boolean b -> sb.append(b);
            case Number n -> sb.append(number(n));
            case Map<?, ?> map -> {
                Map<String, Object> sorted = new TreeMap<>();
                map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> e : sorted.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    quote(sb, e.getKey());
                    sb.append(':');
                    append(sb, e.getValue());
                }
                sb.append('}');
            }
            case Iterable<?> it -> {
                List<Object> list = new ArrayList<>();
                it.forEach(list::add);
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    append(sb, list.get(i));
                }
                sb.append(']');
            }
            default -> quote(sb, String.valueOf(value));
        }
    }

    /** 数字统一成「最小表示」形式：1、1.0、1.00 归一到 1，避免同一语义拿到不同键。 */
    private static String number(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
            return String.valueOf(n.longValue());
        }
        BigDecimal bd = new BigDecimal(n.toString()).stripTrailingZeros();
        return bd.toPlainString();
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }
}
