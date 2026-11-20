package com.aics.eval;

import com.aics.chat.ChatOrchestrator;
import com.aics.chat.LlmClient;
import com.aics.chat.SessionStore;
import com.aics.common.AicsProperties;
import com.aics.common.CollectingSink;
import com.aics.common.TraceContext;
import com.aics.retrieval.RetrievalResult;
import com.aics.retrieval.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 评测执行器（技术设计 §3.9.3）。
 *
 * <pre>
 * java -jar aics.jar --eval=eval/cases.jsonl --out=eval/report-2026-11-21.md
 * java -jar aics.jar --eval=eval/cases.jsonl --mode=retrieval
 * </pre>
 *
 * <p>默认走进程内服务调用而非 HTTP，速度快且不受 SSE 影响。
 */
@Component
public class EvalRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    public record CaseResult(
            String id,
            String question,
            boolean shouldRefuse,
            boolean refused,
            boolean pass,
            int hitRank,
            double topScore,
            int citedCount,
            long ttftMs,
            String answer,
            String refsSummary) {
    }

    public record Report(
            String mode,
            int topK,
            double threshold,
            String model,
            List<CaseResult> results,
            Double recallAt5,
            Double refusalAccuracy,
            Double citationCoverage,
            long ttftP50,
            long ttftP95,
            Path reviewCsv,
            List<String> notes) {
    }

    private final RetrievalService retrieval;
    private final ChatOrchestrator orchestrator;
    private final SessionStore store;
    private final AicsProperties props;
    private final ApplicationContext context;

    public EvalRunner(RetrievalService retrieval, ChatOrchestrator orchestrator, SessionStore store,
                      AicsProperties props, ApplicationContext context) {
        this.retrieval = retrieval;
        this.orchestrator = orchestrator;
        this.store = store;
        this.props = props;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String evalPath = option(args, "eval");
        if (evalPath == null) {
            return;
        }
        String mode = Optional.ofNullable(option(args, "mode")).orElse("retrieval");
        Path casesPath = Path.of(evalPath);
        if (!casesPath.toFile().exists()) {
            log.error("评测集不存在: {}", casesPath.toAbsolutePath());
            System.exit(2);
            return;
        }

        List<EvalCase> cases = EvalCase.load(casesPath);
        log.info("开始评测 mode={} cases={}", mode, cases.size());

        List<CaseResult> results = new ArrayList<>();
        for (EvalCase c : cases) {
            try {
                results.add("full".equalsIgnoreCase(mode) ? runFull(c) : runRetrieval(c));
            } catch (RuntimeException e) {
                log.error("用例 {} 执行失败: {}", c.id(), e.toString());
                results.add(new CaseResult(c.id(), c.question(), c.shouldRefuse(), false, false, 0, 0, 0, 0,
                        "", "执行失败：" + e.getMessage()));
            }
        }

        Path out = Path.of(Optional.ofNullable(option(args, "out"))
                .orElse("eval/report-" + LocalDate.now() + ".md"));
        Path reviewCsv = out.resolveSibling(out.getFileName().toString().replaceAll("\\.md$", "") + "-review.csv");
        Report report = summarize(mode, results, reviewCsv);
        ReportWriter.writeMarkdown(out, report);
        if ("full".equalsIgnoreCase(mode)) {
            ReportWriter.writeReviewCsv(reviewCsv, results);
        }

        printSummary(report, out);
        int exit = results.stream().allMatch(CaseResult::pass) ? 0 : 1;
        SpringApplication.exit(context, () -> exit);
    }

    // ---------------------------------------------------------------- 两种模式

    private CaseResult runRetrieval(EvalCase c) {
        TraceContext trace = new TraceContext();
        RetrievalResult result = retrieval.retrieve(c.question(), historyOf(c), false, trace);
        int hitRank = hitRank(result.candidates(), c, 5);
        boolean refused = result.refs().isEmpty();
        boolean pass = c.shouldRefuse() ? refused : hitRank > 0;
        String refs = result.candidates().stream()
                .map(x -> x.rank() + ". " + x.doc() + " · " + x.headingText() + " (" + String.format("%.2f", x.score()) + ")")
                .reduce((a, b) -> a + " | " + b).orElse("");
        return new CaseResult(c.id(), c.question(), c.shouldRefuse(), refused, pass, hitRank,
                result.topScore(), 0, 0, "", refs);
    }

    private CaseResult runFull(EvalCase c) {
        long sessionId = store.createSession("[eval] " + c.id());
        int seq = 1;
        for (EvalCase.HistoryItem item : c.historyOrEmpty()) {
            store.appendMessage(sessionId, seq++, item.role(), item.content(), null, null, null);
        }
        CollectingSink sink = new CollectingSink();
        ChatOrchestrator.ChatResult result = orchestrator.handle(sessionId, c.question(), sink);

        int hitRank = hitRank(result.candidates(), c, 5);
        boolean refused = result.refused();
        boolean pass = c.shouldRefuse() ? refused : (hitRank > 0 && (!result.cited().isEmpty() || result.refs().isEmpty()));
        String refs = result.refs().stream()
                .map(r -> r.index() + ". " + r.doc() + " · " + r.sec() + " (" + String.format("%.2f", r.score()) + ")")
                .reduce((a, b) -> a + " | " + b).orElse("");
        return new CaseResult(c.id(), c.question(), c.shouldRefuse(), refused, pass, hitRank,
                result.refs().isEmpty() ? 0 : result.refs().getFirst().score(),
                result.cited().size(), result.ttftMs(), result.answer(), refs);
    }

    // ---------------------------------------------------------------- 指标

    private Report summarize(String mode, List<CaseResult> results, Path reviewCsv) {
        List<CaseResult> answerable = results.stream().filter(r -> !r.shouldRefuse()).toList();
        List<CaseResult> negatives = results.stream().filter(CaseResult::shouldRefuse).toList();

        Double recall = answerable.isEmpty() ? null
                : (double) answerable.stream().filter(r -> r.hitRank() > 0).count() / answerable.size();
        Double refusalAccuracy = negatives.isEmpty() ? null
                : (double) negatives.stream().filter(CaseResult::refused).count() / negatives.size();

        Double citationCoverage = null;
        if ("full".equalsIgnoreCase(mode)) {
            List<CaseResult> knowledge = answerable.stream().filter(r -> !r.refused()).toList();
            if (!knowledge.isEmpty()) {
                citationCoverage = (double) knowledge.stream().filter(r -> r.citedCount() > 0).count()
                        / knowledge.size();
            }
        }

        List<Long> ttfts = results.stream().map(CaseResult::ttftMs).filter(t -> t > 0).sorted().toList();

        List<String> notes = new ArrayList<>();
        notes.add("首字延迟来自 " + ttfts.size() + " 次真实请求；生成类指标建议重复 3 次取均值再下结论。");
        notes.add("阈值 " + props.getRetrieval().getScoreThreshold()
                + " 需按本报告的 top1 分数分布校准（正例与负例各画一条分布，取误判代价最小的切点）。");
        if ("retrieval".equalsIgnoreCase(mode)) {
            notes.add("retrieval 模式只验证召回与拒答，不产生生成费用，引用覆盖率为空属预期。");
        }

        return new Report(mode, props.getRetrieval().getTopK(), props.getRetrieval().getScoreThreshold(),
                props.getLlm().getModel(), results, recall, refusalAccuracy, citationCoverage,
                percentile(ttfts, 0.50), percentile(ttfts, 0.95), reviewCsv, notes);
    }

    static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    /** 命中判定：文档匹配 + （章节匹配 或 关键词出现）。 */
    static int hitRank(List<RetrievalResult.Candidate> candidates, EvalCase c, int within) {
        if (c.shouldRefuse() || candidates.isEmpty()) {
            return 0;
        }
        for (RetrievalResult.Candidate candidate : candidates) {
            if (candidate.rank() > within) {
                break;
            }
            boolean docOk = isBlank(c.expectDoc()) || candidate.doc().contains(c.expectDoc())
                    || c.expectDoc().contains(candidate.doc());
            if (!docOk) {
                continue;
            }
            boolean sectionOk = isBlank(c.expectSection())
                    || candidate.headingText().contains(c.expectSection())
                    || c.expectSection().contains(candidate.headingText())
                    // 合并后的切片标题保留的是首个兄弟节点，被合并的章节标题仍在正文里
                    || candidate.content().contains(c.expectSection());
            boolean keywordOk = !c.keywords().isEmpty()
                    && c.keywords().stream().anyMatch(k -> candidate.content().contains(k));
            if (sectionOk || keywordOk) {
                return candidate.rank();
            }
        }
        return 0;
    }

    private static List<LlmClient.PromptMessage> historyOf(EvalCase c) {
        return c.historyOrEmpty().stream()
                .map(h -> new LlmClient.PromptMessage(h.role(), h.content()))
                .toList();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String option(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private void printSummary(Report report, Path out) {
        System.out.println();
        System.out.println("=== AICS 评测结果（" + report.mode() + "）===");
        System.out.println("用例数        : " + report.results().size());
        System.out.println("Recall@5      : " + fmt(report.recallAt5()));
        System.out.println("拒答准确率    : " + fmt(report.refusalAccuracy()));
        System.out.println("引用覆盖率    : " + fmt(report.citationCoverage()));
        System.out.println("首字 p50/p95  : " + report.ttftP50() + "ms / " + report.ttftP95() + "ms");
        System.out.println("报告          : " + out.toAbsolutePath());
        System.out.println();
    }

    private static String fmt(Double value) {
        return value == null ? "—" : String.format("%.1f%%", value * 100);
    }
}
