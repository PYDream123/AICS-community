package com.aics.chat;

import com.aics.common.AicsProperties;
import com.aics.retrieval.RetrievalResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 组装（技术设计 §6.1）。
 *
 * <p>三条设计要点：
 * <ol>
 *   <li>编号与来源由服务端注入，模型只标角标 → 引用可校验、可回填；</li>
 *   <li>规则 1 与 L1 阈值形成双保险：阈值挡明显无关，规则 1 挡「沾边但不足以下结论」；</li>
 *   <li>规则 3 防泄漏：不许说「根据知识库」，否则客服话术立刻破功。</li>
 * </ol>
 */
@Component
public class PromptBuilder {

    private static final String RULES = """
            你是「AICS 智能客服」，为%s的客户提供售前售后支持。

            回答规则：
            1. 只能依据【参考资料】回答。资料中没有的内容，直接说明「暂无相关资料」，禁止推测或补充常识。
            2. 每句结论后标注来源编号，格式为 [1]、[2]，编号必须来自参考资料。
            3. 不要提及「参考资料」「知识库」「片段」等实现细节，用「根据我们的售后服务政策」这类自然表述。
            4. 语气简洁、克制、可执行；不使用感叹号，不做承诺性表述（如「一定」「保证」）。
            5. 涉及金额、时限、保修范围时，必须引用原文数字，不得换算或概括。
            6. 需要业务数据（订单、物流）时，调用工具获取，不要编造。
            """;

    private final AicsProperties props;

    public PromptBuilder(AicsProperties props) {
        this.props = props;
    }

    public String systemPrompt(String sessionSummary, List<RetrievalResult.RetrievedChunk> refs) {
        StringBuilder sb = new StringBuilder(RULES.formatted(props.getPrompt().getCompanyName()));
        if (sessionSummary != null && !sessionSummary.isBlank()) {
            sb.append("\n【对话背景】\n").append(sessionSummary.strip()).append('\n');
        }
        if (refs == null || refs.isEmpty()) {
            sb.append("\n【参考资料】\n（本轮没有检索到资料，不要编造内容）\n");
            return sb.toString();
        }
        sb.append("\n【参考资料】\n");
        int maxChars = props.getPrompt().getMaxChunkCharsInPrompt();
        for (RetrievalResult.RetrievedChunk ref : refs) {
            String source = ref.doc() + (ref.headingText() == null || ref.headingText().isBlank()
                    ? "" : " · " + ref.headingText());
            String content = ref.content() == null ? "" : ref.content().strip();
            if (content.length() > maxChars) {
                content = content.substring(0, maxChars) + "…";
            }
            sb.append('[').append(ref.index()).append("] 来源：").append(source).append('\n')
                    .append(content).append("\n\n");
        }
        return sb.toString();
    }

    public String userPrompt(String question) {
        return question;
    }
}
