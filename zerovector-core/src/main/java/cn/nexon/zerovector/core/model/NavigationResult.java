package cn.nexon.zerovector.core.model;

import cn.nexon.zerovector.core.ai.LLMUsageStats;

import java.util.List;

/**
 * 导航结果记录
 * 包含搜索结果、推理路径和 LLM 调用统计数据
 *
 * @param documents 搜索结果文档列表
 * @param reasoning 推理说明
 * @param path 导航路径
 * @param llmUsageStats LLM 调用统计数据
 */
public record NavigationResult(
        List<DocumentChunk> documents,
        String reasoning,
        List<NavigationPath> path,
        LLMUsageStats llmUsageStats
) {
    public NavigationResult {
        if (llmUsageStats == null) {
            llmUsageStats = new LLMUsageStats();
        }
    }
    
    public static NavigationResult of(List<DocumentChunk> documents, String reasoning, List<NavigationPath> path) {
        return new NavigationResult(documents, reasoning, path, new LLMUsageStats());
    }
    
    public static NavigationResult of(List<DocumentChunk> documents, String reasoning, List<NavigationPath> path, LLMUsageStats llmUsageStats) {
        return new NavigationResult(documents, reasoning, path, llmUsageStats);
    }
}
