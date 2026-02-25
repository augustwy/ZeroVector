package cn.nexon.zerovector.core.model;

import cn.nexon.zerovector.core.ai.LLMUsageStats;

/**
 * 文档上传结果
 * 包含上传处理结果和 LLM 调用统计信息
 *
 * @param documentId 文档 ID
 * @param documentTitle 文档标题
 * @param success 是否成功
 * @param message 消息
 * @param skipped 是否跳过（已存在）
 * @param llmUsageStats LLM 调用统计
 */
public record DocumentUploadResult(
    String documentId,
    String documentTitle,
    boolean success,
    String message,
    boolean skipped,
    LLMUsageStats llmUsageStats
) {
    public static DocumentUploadResult success(String documentId, String documentTitle, LLMUsageStats stats) {
        return new DocumentUploadResult(documentId, documentTitle, true, "文档上传成功", false, stats);
    }
    
    public static DocumentUploadResult skipped(String documentId, String reason) {
        return new DocumentUploadResult(documentId, null, true, reason, true, new LLMUsageStats());
    }
    
    public static DocumentUploadResult failure(String documentId, String message) {
        return new DocumentUploadResult(documentId, null, false, message, false, new LLMUsageStats());
    }
}
