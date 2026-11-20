package com.aics.kb;

import com.aics.common.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** 知识库接口（技术设计 §5.1），字段命名与 demo 现有结构对齐。 */
@RestController
@RequestMapping("/api/kb")
public class KbController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final IngestService ingestService;
    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;

    public KbController(IngestService ingestService, KbDocumentRepository documents, KbChunkRepository chunks) {
        this.ingestService = ingestService;
        this.documents = documents;
        this.chunks = chunks;
    }

    public record DocumentView(
            long id,
            String name,
            String size,
            long sizeBytes,
            int chunks,
            String status,
            String statusText,
            String time,
            String error) {
    }

    public record UploadResponse(long id, String filename, String status, boolean reused) {
    }

    public record ChunkView(int index, String headingPath, String headingText, int headingLevel,
                            String content, int tokens, boolean oversized) {
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw AppException.badRequest("请选择要上传的文件");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw AppException.badRequest("读取上传文件失败");
        }
        IngestService.UploadResult result = ingestService.upload(file.getOriginalFilename(), bytes);
        return new UploadResponse(result.id(), result.filename(), result.status(), result.reused());
    }

    @GetMapping("/documents")
    public List<DocumentView> list() {
        return documents.findAll().stream().map(KbController::toView).toList();
    }

    @GetMapping("/documents/{id}/chunks")
    public List<ChunkView> chunks(@PathVariable long id) {
        documents.findById(id).orElseThrow(() -> AppException.notFound("文档"));
        return chunks.findByDoc(id).stream()
                .map(c -> new ChunkView(c.chunkIndex(), c.headingPath(), c.headingText(),
                        c.headingLevel(), c.content(), c.tokenCount(), c.oversized()))
                .toList();
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        documents.findById(id).orElseThrow(() -> AppException.notFound("文档"));
        // 物理删除 + ON DELETE CASCADE：切片与向量同步清除（产品验收点）
        documents.delete(id);
    }

    @PostMapping("/documents/{id}/rebuild")
    public DocumentView rebuild(@PathVariable long id) {
        ingestService.rebuild(id);
        return documents.findById(id).map(KbController::toView)
                .orElseThrow(() -> AppException.notFound("文档"));
    }

    private static DocumentView toView(KbDocumentRepository.KbDocument doc) {
        String status = switch (doc.status()) {
            case "READY" -> "ready";
            case "FAILED" -> "failed";
            default -> "processing";
        };
        String statusText = switch (doc.status()) {
            case "READY" -> "可用";
            case "FAILED" -> "失败";
            case "PROCESSING" -> "解析中";
            default -> "排队中";
        };
        return new DocumentView(
                doc.id(),
                doc.filename(),
                humanSize(doc.sizeBytes()),
                doc.sizeBytes(),
                doc.chunkCount(),
                status,
                statusText,
                doc.uploadedAt() == null ? "" : TIME.format(doc.uploadedAt()),
                doc.errorMsg());
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.0f KB", kb);
        }
        return String.format(Locale.ROOT, "%.1f MB", kb / 1024.0);
    }
}
