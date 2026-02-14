package cn.nexon.zerovector.core.document;

import java.util.ArrayList;
import java.util.List;

public final class DocumentSplitter {

    private final int maxContextTokens;
    private final int chunkOverlapTokens;
    private final int minChunkTokens;

    public DocumentSplitter(int maxContextTokens, int chunkOverlapTokens, int minChunkTokens) {
        this.maxContextTokens = maxContextTokens;
        this.chunkOverlapTokens = chunkOverlapTokens;
        this.minChunkTokens = minChunkTokens;
    }

    public List<String> split(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }

        int estimatedTokens = estimateTokens(content);
        
        if (estimatedTokens <= maxContextTokens) {
            return List.of(content);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        int contentLength = content.length();
        
        while (start < contentLength) {
            int availableTokens = maxContextTokens;
            int end = start + (int) ((long) contentLength * availableTokens / estimatedTokens);
            
            if (end > contentLength) {
                end = contentLength;
            }

            String chunk = content.substring(start, end);
            
            if (chunk.trim().isEmpty()) {
                break;
            }

            end = adjustChunkBoundary(content, start, end);
            chunk = content.substring(start, end);
            
            chunks.add(chunk);
            
            start = end - (int) ((long) contentLength * chunkOverlapTokens / estimatedTokens);
            if (start < end - minChunkTokens) {
                start = end - minChunkTokens;
            }
            
            if (start >= end) {
                break;
            }
        }

        return chunks;
    }

    private int adjustChunkBoundary(String content, int start, int end) {
        if (end >= content.length()) {
            return end;
        }

        String chunk = content.substring(start, end);
        
        int lastPeriod = chunk.lastIndexOf('。');
        int lastNewline = chunk.lastIndexOf('\n');
        int lastSpace = chunk.lastIndexOf(' ');
        
        int bestBoundary = Math.max(lastPeriod, Math.max(lastNewline, lastSpace));
        
        if (bestBoundary > start + minChunkTokens * (end - start) / maxContextTokens) {
            return start + bestBoundary + 1;
        }
        
        return end;
    }

    private int estimateTokens(String text) {
        return text.length();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int maxContextTokens = 4000;
        private int chunkOverlapTokens = 200;
        private int minChunkTokens = 500;

        public Builder maxContextTokens(int maxContextTokens) {
            this.maxContextTokens = maxContextTokens;
            return this;
        }

        public Builder chunkOverlapTokens(int chunkOverlapTokens) {
            this.chunkOverlapTokens = chunkOverlapTokens;
            return this;
        }

        public Builder minChunkTokens(int minChunkTokens) {
            this.minChunkTokens = minChunkTokens;
            return this;
        }

        public DocumentSplitter build() {
            return new DocumentSplitter(maxContextTokens, chunkOverlapTokens, minChunkTokens);
        }
    }
}
