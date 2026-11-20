package com.aics.chat;

/** 前端引用条的数据结构：字段与 demo 现有 {doc, sec, score, snip} 对齐。 */
public record RefView(int index, long chunkId, String doc, String sec, double score, String snip) {

    private static final int SNIP_LENGTH = 120;

    public static RefView of(com.aics.retrieval.RetrievalResult.RetrievedChunk chunk) {
        String content = chunk.content() == null ? "" : chunk.content().replace("\n", " ").strip();
        String snip = content.length() <= SNIP_LENGTH ? content : content.substring(0, SNIP_LENGTH) + "…";
        return new RefView(chunk.index(), chunk.chunkId(), chunk.doc(), chunk.headingText(), chunk.score(), snip);
    }
}
