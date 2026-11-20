package com.aics.chat;

import com.aics.common.AicsProperties;
import com.aics.support.FakeLlmClient;
import com.aics.support.InMemorySessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 多轮记忆：近期窗口、摘要触发条件、summary_upto_seq 前移（技术设计 §3.6 / §9.1）。 */
class MemoryServiceTest {

    private final InMemorySessionStore store = new InMemorySessionStore();
    private final FakeLlmClient llm = new FakeLlmClient();
    private final AicsProperties props = new AicsProperties();
    private final MemoryService memory = new MemoryService(llm, store, props);

    private long sessionWithTurns(int turns) {
        long id = store.createSession("测试会话");
        int seq = 1;
        for (int i = 1; i <= turns; i++) {
            store.appendMessage(id, seq++, "user", "第 " + i + " 轮问题", null, null, null);
            store.appendMessage(id, seq++, "assistant", "第 " + i + " 轮回答", null, null, null);
        }
        return id;
    }

    @Test
    @DisplayName("近期窗口只取最近 6 轮（12 条）")
    void recentWindowKeepsLastTurns() {
        long id = sessionWithTurns(10);
        List<SessionStore.Message> all = store.messages(id);

        List<SessionStore.Message> window = MemoryService.recentWindow(all, 6);

        assertThat(window).hasSize(12);
        assertThat(window.getFirst().content()).isEqualTo("第 5 轮问题");
        assertThat(window.getLast().content()).isEqualTo("第 10 轮回答");
    }

    @Test
    @DisplayName("8 轮对话：Prompt 历史包含前文关键信息且不超窗口")
    void keepsContextWithinEightTurns() {
        long id = sessionWithTurns(8);
        SessionStore.Session session = store.findSession(id).orElseThrow();

        MemoryService.MemoryView view = memory.view(session, store.messages(id));

        assertThat(view.history()).hasSize(12); // 最近 6 轮
        assertThat(view.history().getLast().content()).isEqualTo("第 8 轮回答");
        assertThat(view.userTurns()).isEqualTo(8);
        assertThat(view.summary()).isNull();
    }

    @Test
    @DisplayName("未超过 12 轮不触发压缩（压缩要占首字预算）")
    void noCompactionBelowThreshold() {
        long id = sessionWithTurns(8);
        SessionStore.Session session = store.findSession(id).orElseThrow();

        memory.maybeCompact(session, store.messages(id));

        assertThat(llm.completeRequests).isEmpty();
        assertThat(store.findSession(id).orElseThrow().summary()).isNull();
    }

    @Test
    @DisplayName("超过 12 轮触发压缩，summary_upto_seq 前移到窗口之前")
    void compactsBeyondThreshold() {
        long id = sessionWithTurns(14);
        SessionStore.Session session = store.findSession(id).orElseThrow();

        SessionStore.Session updated = memory.maybeCompact(session, store.messages(id));

        assertThat(llm.completeRequests).hasSize(1);
        assertThat(updated.summary()).isNotBlank();
        assertThat(updated.summaryUptoSeq()).isGreaterThan(0);
        // 摘要覆盖到「近期窗口之前」，即第 8 轮回答（seq=16）
        assertThat(updated.summaryUptoSeq()).isEqualTo(16);
        assertThat(store.findSession(id).orElseThrow().summary()).isNotBlank();
    }

    @Test
    @DisplayName("待压缩区间不包含已摘要部分与近期窗口")
    void selectsOnlyCompressibleRange() {
        long id = sessionWithTurns(14);
        List<SessionStore.Message> all = store.messages(id);

        List<SessionStore.Message> candidates = MemoryService.selectCompactionCandidates(all, 4, 6);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates).allSatisfy(m -> assertThat(m.seq()).isGreaterThan(4));
        assertThat(candidates.getLast().seq()).isLessThan(all.get(all.size() - 12).seq());
    }

    @Test
    @DisplayName("压缩结果超过 300 字时被截断，避免反过来撑大 Prompt")
    void trimsSummary() {
        long id = sessionWithTurns(14);
        SessionStore.Session session = store.findSession(id).orElseThrow();
        llm.completeResult = "摘要".repeat(400);

        SessionStore.Session updated = memory.maybeCompact(session, store.messages(id));

        assertThat(updated.summary()).hasSize(props.getMemory().getSummaryMaxChars());
    }
}
