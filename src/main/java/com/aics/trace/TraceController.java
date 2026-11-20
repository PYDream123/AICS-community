package com.aics.trace;

import com.aics.common.AppException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Trace 查询（技术设计 §5.3）：调试面板的数据来源。 */
@RestController
@RequestMapping("/api/trace")
public class TraceController {

    private final TraceRecorder recorder;

    public TraceController(TraceRecorder recorder) {
        this.recorder = recorder;
    }

    @GetMapping("/{traceId}")
    public TraceRecorder.TraceView detail(@PathVariable String traceId) {
        return recorder.find(traceId).orElseThrow(() -> AppException.notFound("Trace"));
    }

    @GetMapping
    public List<TraceRecorder.TraceView> recent(@RequestParam(defaultValue = "20") int limit) {
        return recorder.recent(Math.min(Math.max(limit, 1), 100));
    }
}
