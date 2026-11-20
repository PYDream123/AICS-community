package com.aics.kb;

import com.aics.common.AppException;
import com.aics.common.IdempotencyKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 知识入库（技术设计 §3.1）：状态机 PENDING → PROCESSING → READY | FAILED。
 *
 * <p>上传接口只做「校验 + 落库 + 提交线程池」，解析与向量化异步完成，
 * 避免 Multipart 请求被 Embedding 调用拖到几秒以上。
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;
    private final MarkdownChunker chunker;
    private final EmbeddingService embeddings;
    private final TransactionTemplate tx;
    private final ThreadPoolTaskExecutor ingestExecutor;

    public IngestService(KbDocumentRepository documents,
                         KbChunkRepository chunks,
                         MarkdownChunker chunker,
                         EmbeddingService embeddings,
                         TransactionTemplate tx,
                         @Qualifier("ingestExecutor") ThreadPoolTaskExecutor ingestExecutor) {
        this.documents = documents;
        this.chunks = chunks;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.tx = tx;
        this.ingestExecutor = ingestExecutor;
    }

    public record UploadResult(long id, String filename, String status, boolean reused) {
    }

    public UploadResult upload(String originalFilename, byte[] content) {
        String filename = sanitizeFilename(originalFilename);
        validate(filename, content);

        String sha = IdempotencyKeys.sha256Hex(content);
        Optional<KbDocumentRepository.KbDocument> existing = documents.findBySha256(sha).stream()
                .filter(d -> "READY".equals(d.status()))
                .findFirst();
        if (existing.isPresent()) {
            // 同内容重复上传直接复用已有文档，不重复计费
            KbDocumentRepository.KbDocument doc = existing.get();
            return new UploadResult(doc.id(), doc.filename(), doc.status(), true);
        }

        String text;
        try {
            text = decode(content);
        } catch (CharacterCodingException e) {
            long id = documents.insert(filename, content.length, sha);
            documents.markFailed(id, "文件编码无法识别，请另存为 UTF-8 后重试");
            return new UploadResult(id, filename, "FAILED", false);
        }

        long id = documents.insert(filename, content.length, sha);
        ingestExecutor.submit(() -> process(id, text));
        return new UploadResult(id, filename, "PENDING", false);
    }

    /** 用当前切片参数重新切片（M5 调参用）。 */
    public void rebuild(long docId) {
        KbDocumentRepository.KbDocument doc = documents.findById(docId)
                .orElseThrow(() -> AppException.notFound("文档"));
        // 原始内容不入库，重建依赖已落库切片拼回原文（按 chunk_index 顺序）
        List<KbChunkRepository.ChunkRow> rows = chunks.findByDoc(docId);
        if (rows.isEmpty()) {
            throw AppException.badRequest("文档没有可用切片，无法重建，请重新上传");
        }
        String markdown = rebuildSource(rows);
        documents.markProcessing(docId);
        tx.executeWithoutResult(status -> chunks.deleteByDoc(docId));
        ingestExecutor.submit(() -> process(docId, markdown));
    }

    private String rebuildSource(List<KbChunkRepository.ChunkRow> rows) {
        StringBuilder sb = new StringBuilder();
        String lastPath = null;
        for (KbChunkRepository.ChunkRow row : rows) {
            if (!row.headingPath().equals(lastPath) && !row.headingPath().isBlank()) {
                String[] parts = row.headingPath().split(" > ");
                int level = Math.min(6, Math.max(1, parts.length));
                sb.append("#".repeat(level)).append(' ').append(row.headingText()).append('\n');
                lastPath = row.headingPath();
            }
            sb.append(row.content()).append("\n\n");
        }
        return sb.toString();
    }

    private void process(long docId, String text) {
        try {
            documents.markProcessing(docId);
            List<MarkdownChunker.Chunk> parsed = chunker.chunk(text);
            if (parsed.isEmpty()) {
                documents.markFailed(docId, "未解析到有效内容，请检查文件是否为纯文本 Markdown");
                return;
            }

            List<String> contents = parsed.stream().map(MarkdownChunker.Chunk::content).toList();
            List<String> shas = contents.stream().map(IdempotencyKeys::sha256Hex).toList();

            // 先查缓存，只对缺失的部分调用 Embedding
            List<float[]> vectors = new ArrayList<>(contents.size());
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < contents.size(); i++) {
                Optional<float[]> cached = chunks.findEmbeddingBySha(shas.get(i));
                if (cached.isPresent()) {
                    vectors.add(cached.get());
                } else {
                    vectors.add(null);
                    missing.add(i);
                }
            }
            if (!missing.isEmpty()) {
                List<float[]> fresh = embeddings.embedDocuments(missing.stream().map(contents::get).toList());
                for (int k = 0; k < missing.size(); k++) {
                    vectors.set(missing.get(k), fresh.get(k));
                }
            }

            tx.executeWithoutResult(status -> {
                chunks.deleteByDoc(docId);
                for (int i = 0; i < parsed.size(); i++) {
                    chunks.insert(docId, parsed.get(i), shas.get(i), vectors.get(i));
                }
            });
            documents.markReady(docId, parsed.size());
            log.info("文档入库完成 docId={} chunks={}（复用向量 {} 条）", docId, parsed.size(),
                    contents.size() - missing.size());
        } catch (EmbeddingService.EmbeddingUnavailableException e) {
            log.error("文档入库失败（向量化不可用） docId={}", docId, e);
            documents.markFailed(docId, "向量化服务暂时不可用，请稍后点击重试");
        } catch (RuntimeException e) {
            log.error("文档入库失败 docId={}", docId, e);
            documents.markFailed(docId, readableReason(e));
        }
    }

    /** error_msg 必须是用户能看懂的话，而不是堆栈（产品验收点）。 */
    private static String readableReason(RuntimeException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("维度不匹配") || msg.contains("dimensions")) {
            return "向量维度与配置不一致，请联系管理员检查 Embedding 模型配置";
        }
        if (msg.contains("401") || msg.contains("Unauthorized")) {
            return "向量化服务鉴权失败，请检查 Embedding API Key";
        }
        return "解析失败，请稍后重试（如持续失败请联系管理员）";
    }

    private void validate(String filename, byte[] content) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".md") && !lower.endsWith(".markdown") && !lower.endsWith(".txt")) {
            throw AppException.badRequest("仅支持 .md / .txt 文件");
        }
        if (content == null || content.length == 0) {
            throw AppException.badRequest("文件内容为空");
        }
        if (content.length > MAX_BYTES) {
            throw AppException.badRequest("文件超过 10 MB 限制");
        }
    }

    private static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "未命名文档.md";
        }
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[\\p{Cntrl}]", "").strip();
        if (base.isEmpty()) {
            base = "未命名文档.md";
        }
        return base.length() > 255 ? base.substring(base.length() - 255) : base;
    }

    private String decode(byte[] content) throws CharacterCodingException {
        String text = strictDecode(content, StandardCharsets.UTF_8);
        if (text == null) {
            text = strictDecode(content, Charset.forName("GBK"));
        }
        if (text == null) {
            throw new CharacterCodingException();
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    private static String strictDecode(byte[] content, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
