package com.aics.common;

/** 粗略 token 估算：中文按字计，英文按词计。只用于预算控制与展示，不要求精确。 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int cjk = 0;
        int latinChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF || c >= 0x3000 && c <= 0x303F || c >= 0xFF00 && c <= 0xFFEF) {
                cjk++;
            } else if (!Character.isWhitespace(c)) {
                latinChars++;
            }
        }
        int latinTokens = (int) Math.ceil(latinChars / 3.2);
        return Math.max(1, cjk + latinTokens);
    }
}
