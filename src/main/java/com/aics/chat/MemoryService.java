package com.aics.chat;

import com.aics.common.AicsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 多轮记忆（技术设计 §3.6）：
 * 近期保留 {@code recent-turns} 轮原文，远期压缩进 {@code chat_session.summary}。
 *
 * <p>压缩只在「轮次跨越阈值」的那一次发生，而不是每轮都做——它要占首字预算。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private static final String SUMMARY_SYSTEM = """
            把以下客服对话压缩为不超过 300 字的摘要，用于后续对话的上下文。

            保留：用户身份与诉求、已确认的事实（订单号、故障现象）、已给出的结论、待办事项。
            丢弃：寒暄、重复表述、工具返回的原始字段。
            只输出摘要正文，不要加标题或解释。
            """;

    private final LlmClient llm;
    private final SessionStore store;
    private final AicsProperties props;

    public MemoryService(LlmClient llm, SessionStore store, AicsProperties props) {
        this.llm = llm;
        this.store = store;
        this.props = props;
    }

    /** Prompt 需要的记忆视图：摘要 + 最近若干轮原文。 */
    public record MemoryView(String summary, List<LlmClient.PromptMessage> history, int userTurns) {
    }

    public MemoryView view(SessionStore.Session session, List<SessionStore.Message> messages) {
        List<SessionStore.Message> window = recentWindow(messages, props.getMemory().getRecentTurns());
        List<LlmClient.PromptMessage> history = new ArrayList<>();
        for (SessionStore.Message m : window) {
            if ("user".equals(m.role()) || "assistant".equals(m.role())) {
                history.add(new LlmClient.PromptMessage(m.role(), m.content()));
            }
        }
        return new MemoryView(session.summary(), history, countUserTurns(messages));
    }

    /**
     * 超过阈值时压缩一次。调用方在流式开始前同步调用。
     *
     * @return 压缩后最新的会话视图（可能带新摘要）
     */
    public SessionStore.Session maybeCompact(SessionStore.Session session, List<SessionStore.Message> messages) {
        AicsProperties.Memory cfg = props.getMemory();
        int turns = countUserTurns(messages);
        if (turns <= cfg.getSummaryTriggerTurns()) {
            return session;
        }
        List<SessionStore.Message> candidates = selectCompactionCandidates(
                messages, session.summaryUptoSeq(), cfg.getRecentTurns());
        if (candidates.size() < 4) {
            return session;
        }
        String dialogue = render(candidates);
        String summary = llm.complete(new LlmClient.CompleteRequest(SUMMARY_SYSTEM, dialogue, 0.0, 400));
        if (summary == null || summary.isBlank()) {
            return session;
        }
        String trimmed = summary.strip();
        if (trimmed.length() > cfg.getSummaryMaxChars()) {
            trimmed = trimmed.substring(0, cfg.getSummaryMaxChars());
        }
        int uptoSeq = candidates.getLast().seq();
        store.updateSummary(session.id(), trimmed, uptoSeq);
        log.info("会话 {} 触发摘要压缩，覆盖到 seq={}，摘要长度={}", session.id(), uptoSeq, trimmed.length());
        return new SessionStore.Session(session.id(), session.title(), session.status(), trimmed, uptoSeq,
                session.createdAt(), session.updatedAt());
    }

    public static List<SessionStore.Message> recentWindow(List<SessionStore.Message> messages, int recentTurns) {
        int keep = Math.max(1, recentTurns) * 2;
        if (messages.size() <= keep) {
            return messages;
        }
        return messages.subList(messages.size() - keep, messages.size());
    }

    /** 待压缩区间：summary_upto_seq 之后、近期窗口之前的消息。 */
    public static List<SessionStore.Message> selectCompactionCandidates(List<SessionStore.Message> messages,
                                                                       int summaryUptoSeq, int recentTurns) {
        List<SessionStore.Message> window = recentWindow(messages, recentTurns);
        int windowStartSeq = window.isEmpty() ? Integer.MAX_VALUE : window.getFirst().seq();
        List<SessionStore.Message> out = new ArrayList<>();
        for (SessionStore.Message m : messages) {
            if (m.seq() > summaryUptoSeq && m.seq() < windowStartSeq) {
                out.add(m);
            }
        }
        return out;
    }

    public static int countUserTurns(List<SessionStore.Message> messages) {
        return (int) messages.stream().filter(m -> "user".equals(m.role())).count();
    }

    private String render(List<SessionStore.Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (SessionStore.Message m : messages) {
            sb.append("assistant".equals(m.role()) ? "客服：" : "用户：")
                    .append(m.content()).append('\n');
        }
        return sb.toString();
    }
}
