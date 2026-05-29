/*
 * Copyright 2025 nexonlab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
