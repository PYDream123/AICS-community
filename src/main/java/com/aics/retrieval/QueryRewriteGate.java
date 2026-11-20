package com.aics.retrieval;

import java.util.regex.Pattern;

/**
 * 查询改写门控（技术设计 §3.3.1）。
 *
 * <p>改写要多一次模型往返（几百毫秒），而首字 ≤1.5s 是硬指标，所以只在「大概率需要」时才改写：
 * 无历史不改写、短句改写、命中指代/承接词改写、上一轮在等槽位也改写。
 */
public final class QueryRewriteGate {

    private static final Pattern ANAPHORA = Pattern.compile("那|这个|那个|它|还有|呢|那如果是|继续|上面|刚才");

    private QueryRewriteGate() {
    }

    public static boolean shouldRewrite(String query, int historySize, boolean awaitingSlot) {
        if (query == null || query.isBlank()) {
            return false;
        }
        if (historySize <= 0) {
            return false;
        }
        if (awaitingSlot) {
            return true;
        }
        if (query.replaceAll("\\s", "").length() < 12) {
            return true;
        }
        return ANAPHORA.matcher(query).find();
    }
}
