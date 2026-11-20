package com.aics.retrieval;

import com.aics.chat.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 查询改写（技术设计 §6.2）：把「那过了保修期维修怎么收费」补全成可独立检索的问题。
 *
 * <p>失败即降级为原问题——改写是优化项，不能成为可用性的单点。
 */
@Component
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    private static final String SYSTEM = """
            把用户的最新问题改写成一个不依赖上下文的独立问题，用于文档检索。

            要求：
            - 只输出改写后的问题，不要解释
            - 补全指代（那、这个、它）所指的对象
            - 保留原始关键词，不要扩大范围
            - 若原问题已独立，原样输出
            """;

    private final LlmClient llm;

    public QueryRewriter(LlmClient llm) {
        this.llm = llm;
    }

    /** @return 改写后的问题；失败或不必要时返回原问题 */
    public String rewrite(String query, List<LlmClient.PromptMessage> history) {
        String user = "对话历史：\n" + recentHistory(history) + "\n\n最新问题：" + query;
        String raw = llm.complete(new LlmClient.CompleteRequest(SYSTEM, user, 0.0, 64));
        String cleaned = clean(raw);
        if (cleaned == null || cleaned.length() > 120) {
            return query;
        }
        log.debug("查询改写: [{}] -> [{}]", query, cleaned);
        return cleaned;
    }

    static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String line = raw.strip().lines().findFirst().orElse("").strip();
        line = line.replaceFirst("^(改写后|改写结果|独立问题)[:：]\\s*", "");
        line = line.replaceAll("^[\"'“”「『`]+|[\"'“”」』`]+$", "");
        line = line.strip();
        if (line.isEmpty() || "无".equals(line)) {
            return null;
        }
        return line;
    }

    private String recentHistory(List<LlmClient.PromptMessage> history) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, history.size() - 4);
        for (int i = from; i < history.size(); i++) {
            LlmClient.PromptMessage m = history.get(i);
            sb.append("assistant".equals(m.role()) ? "客服：" : "用户：")
                    .append(m.content()).append('\n');
        }
        return sb.toString();
    }
}
