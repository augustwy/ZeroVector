package cn.nexon.zerovector.springboot;

import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.NavigationPath;

import java.util.List;

/**
 * 搜索结果，封装了文档列表及内容获取逻辑。
 */
public interface SearchResult {
    String query();
    List<DocumentChunk> documents();
    String reasoning();
    List<NavigationPath> path();
    LLMUsageStats llmUsageStats();

    /** 将文档内容拼接为纯文本，优先读文件原文，不可用时回退 summary。 */
    String toDocumentText();

    /** 获取单个 chunk 的实际内容，已封装好 fallback 逻辑。 */
    String getChunkContent(DocumentChunk chunk);
}
