package cn.nexon.zerovector.springboot;

import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.NavigationPath;
import cn.nexon.zerovector.core.model.NavigationResult;
import cn.nexon.zerovector.core.storage.spi.ChunkStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

record SearchResultImpl(
    String query,
    ChunkStorage chunkStorage,
    NavigationResult navigationResult
) implements SearchResult {

    @Override
    public List<DocumentChunk> documents() {
        return navigationResult.documents();
    }

    @Override
    public String reasoning() {
        return navigationResult.reasoning();
    }

    @Override
    public List<NavigationPath> path() {
        return navigationResult.path();
    }

    @Override
    public LLMUsageStats llmUsageStats() {
        return navigationResult.llmUsageStats();
    }

    @Override
    public String toDocumentText() {
        List<DocumentChunk> docs = documents();
        if (docs == null || docs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            sb.append("[").append(i + 1).append("] ");
            sb.append(getChunkContent(docs.get(i))).append("\n\n");
        }
        return sb.toString().trim();
    }

    @Override
    public String getChunkContent(DocumentChunk chunk) {
        if (chunk.isFilePathBased() && chunk.filePath() != null) {
            try {
                return Files.readString(Path.of(chunk.filePath()));
            } catch (IOException e) { /* fall through */ }
        }
        try {
            String content = chunkStorage.getChunkContent(chunk.id());
            if (content != null && !content.isEmpty()) return content;
        } catch (Exception e) { /* fall through */ }
        return chunk.summary() != null ? chunk.summary() : "";
    }
}
