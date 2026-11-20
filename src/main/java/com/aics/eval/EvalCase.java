package com.aics.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 评测用例（技术设计 §3.9.1）。
 *
 * <p>字段设计要点：{@code expectSection} 让检索可被自动判定；{@code history} 让多轮场景可复现；
 * {@code shouldRefuse} 让拒答准确率可计算。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(
        String id,
        String question,
        String expectDoc,
        String expectSection,
        List<String> expectKeywords,
        boolean shouldRefuse,
        List<HistoryItem> history) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HistoryItem(String role, String content) {
    }

    public List<String> keywords() {
        return expectKeywords == null ? List.of() : expectKeywords;
    }

    public List<HistoryItem> historyOrEmpty() {
        return history == null ? List.of() : history;
    }

    public static List<EvalCase> load(Path path) throws IOException {
        List<EvalCase> cases = new ArrayList<>();
        ObjectMapper mapper = JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .build();
        int lineNo = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNo++;
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }
            try {
                cases.add(mapper.readValue(trimmed, new TypeReference<EvalCase>() {
                }));
            } catch (IOException e) {
                throw new IOException("评测集第 " + lineNo + " 行解析失败: " + e.getMessage(), e);
            }
        }
        return cases;
    }
}
