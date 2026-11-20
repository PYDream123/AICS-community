package com.aics.handoff;

import com.aics.common.AppException;
import com.aics.chat.SessionStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 转人工与队列接口（技术设计 §5.3）。 */
@RestController
public class HandoffController {

    private final HandoffService handoff;
    private final SessionStore sessions;

    public HandoffController(HandoffService handoff, SessionStore sessions) {
        this.handoff = handoff;
        this.sessions = sessions;
    }

    public record HandoffRequest(Long sessionId, String reasonCode, String question) {
    }

    public record HandoffResponse(long ticketId, String status) {
    }

    /** 用户点击「转人工」：SUGGESTED → WAITING。 */
    @PostMapping("/api/chat/handoff")
    public HandoffResponse request(@RequestBody HandoffRequest request) {
        if (request.sessionId() == null) {
            throw AppException.badRequest("sessionId 不能为空");
        }
        SessionStore.Session session = sessions.findSession(request.sessionId())
                .orElseThrow(() -> AppException.notFound("会话"));
        String question = request.question();
        if (question == null || question.isBlank()) {
            question = latestUserMessage(session.id());
        }
        handoff.request(session.id(), HandoffService.ReasonCode.of(request.reasonCode()), question);
        return new HandoffResponse(session.id(), "WAITING");
    }

    @GetMapping("/api/handoff/queue")
    public List<HandoffService.QueueItem> queue() {
        return handoff.queue();
    }

    @GetMapping("/api/handoff/stats")
    public HandoffService.Stats stats() {
        return handoff.stats();
    }

    @PatchMapping("/api/handoff/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void handle(@PathVariable long id, @RequestBody(required = false) HandoffRequest body) {
        handoff.markHandled(id);
    }

    private String latestUserMessage(long sessionId) {
        List<SessionStore.Message> messages = sessions.messages(sessionId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                return messages.get(i).content();
            }
        }
        return "（未获取到问题摘要）";
    }
}
