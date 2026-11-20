package com.aics.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/** 评测报告输出：Markdown 报告 + 引用准确率人工评审表（技术设计 §3.9.2）。 */
public final class ReportWriter {

    private ReportWriter() {
    }

    public static Path writeMarkdown(Path out, EvalRunner.Report report) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# AICS 评测报告 ").append(LocalDate.now()).append("\n\n");
        sb.append("| 项 | 值 |\n|---|---|\n");
        sb.append("| 执行模式 | ").append(report.mode()).append(" |\n");
        sb.append("| 用例数 | ").append(report.results().size()).append(" |\n");
        sb.append("| top-k / 阈值 | ").append(report.topK()).append(" / ").append(report.threshold()).append(" |\n");
        sb.append("| 生成模型 | ").append(report.model()).append(" |\n\n");

        sb.append("## 核心指标\n\n");
        sb.append("| 指标 | 值 | 口径 |\n|---|---|---|\n");
        sb.append("| Recall@5 | ").append(pct(report.recallAt5())).append(" | 期望片段出现在 top-5 的可回答用例占比 |\n");
        sb.append("| 拒答准确率 | ").append(pct(report.refusalAccuracy())).append(" | 无答案问题被正确拒答的比例 |\n");
        sb.append("| 引用覆盖率 | ").append(pct(report.citationCoverage())).append(" | 知识类回答携带 ≥1 条有效引用的比例 |\n");
        sb.append("| 首字延迟 p50 / p95 | ").append(report.ttftP50()).append(" ms / ")
                .append(report.ttftP95()).append(" ms | 来自本次真实请求 |\n\n");

        sb.append("> 引用准确率无法自动判定，脚本已导出评审表：`").append(report.reviewCsv()).append("`。")
                .append("人工打勾后回读计算，不使用 LLM-as-judge。\n\n");

        sb.append("## 逐条结果\n\n");
        sb.append("| 用例 | 问题 | 类型 | 结果 | 命中位次 | top1 | 引用 | 首字 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (EvalRunner.CaseResult r : report.results()) {
            sb.append("| ").append(r.id())
                    .append(" | ").append(escape(r.question()))
                    .append(" | ").append(r.shouldRefuse() ? "负例" : "正例")
                    .append(" | ").append(r.pass() ? "PASS" : "FAIL")
                    .append(" | ").append(r.hitRank() > 0 ? String.valueOf(r.hitRank()) : "—")
                    .append(" | ").append(String.format("%.2f", r.topScore()))
                    .append(" | ").append(r.citedCount())
                    .append(" | ").append(r.ttftMs() > 0 ? r.ttftMs() + "ms" : "—")
                    .append(" |\n");
        }
        if (!report.notes().isEmpty()) {
            sb.append("\n## 说明\n\n");
            for (String note : report.notes()) {
                sb.append("- ").append(note).append('\n');
            }
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        return out;
    }

    public static Path writeReviewCsv(Path out, List<EvalRunner.CaseResult> results) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("caseId,question,answer,refs,支撑回答(yes/no)\n");
        for (EvalRunner.CaseResult r : results) {
            sb.append(csv(r.id())).append(',')
                    .append(csv(r.question())).append(',')
                    .append(csv(r.answer())).append(',')
                    .append(csv(r.refsSummary())).append(',')
                    .append('\n');
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        return out;
    }

    private static String pct(Double value) {
        return value == null ? "—" : String.format("%.1f%%", value * 100);
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }

    private static String csv(String text) {
        if (text == null) {
            return "";
        }
        String escaped = text.replace("\"", "\"\"").replace("\n", " ");
        return '"' + escaped + '"';
    }
}
