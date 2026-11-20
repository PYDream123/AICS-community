package com.aics.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用编号解析（技术设计 §6.1）。
 *
 * <p>编号由服务端注入，模型只负责在句末标角标，因此引用是「可校验的」：
 * 跑一遍正则就能抽出 cited 集合回填 trace_retrieval.cited。越界编号直接丢弃，避免引用准确率虚高。
 */
public final class CitationParser {

    private static final Pattern MARK = Pattern.compile("\\[(\\d{1,2})]");

    private CitationParser() {
    }

    public static List<Integer> parse(String answer, int refCount) {
        if (answer == null || answer.isEmpty() || refCount <= 0) {
            return List.of();
        }
        TreeSet<Integer> found = new TreeSet<>();
        Matcher m = MARK.matcher(answer);
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            if (idx >= 1 && idx <= refCount) {
                found.add(idx);
            }
        }
        return new ArrayList<>(found);
    }

    /** 无引用但有结论时，产品要求不得声称「根据资料」——用它做一次事后校验。 */
    public static boolean claimsBasedOnMaterial(String answer) {
        if (answer == null) {
            return false;
        }
        return answer.contains("根据资料") || answer.contains("根据知识库")
                || answer.contains("根据参考资料") || answer.contains("资料显示");
    }
}
