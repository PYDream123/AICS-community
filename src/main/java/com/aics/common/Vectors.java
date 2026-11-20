package com.aics.common;

/** pgvector 字面量转换：JDBC 里以字符串 + CAST(? AS vector) 传入，避免额外的类型映射依赖。 */
public final class Vectors {

    private Vectors() {
    }

    public static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(vector[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    public static float[] fromLiteral(String literal) {
        if (literal == null || literal.isBlank()) {
            return new float[0];
        }
        String body = literal.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i].trim());
        }
        return out;
    }
}
