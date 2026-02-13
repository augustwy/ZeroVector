package cn.nexon.zerovector.core.document.impl;

import cn.nexon.zerovector.core.exception.DocumentProcessingException;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.document.DocumentProcessor;
import cn.nexon.zerovector.core.document.DocumentProcessor.ProcessingConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public abstract class AbstractDocumentProcessor implements DocumentProcessor {
    
    private static final int PARAGRAPH_CHUNK_INDEX_BASE = 1000;
    private static final int SUMMARY_LENGTH = 100;
    
    protected ProcessingConfig config;
    
    protected AbstractDocumentProcessor() {
        this.config = ProcessingConfig.DEFAULT;
    }
    
    protected AbstractDocumentProcessor(ProcessingConfig config) {
        this.config = config;
    }
    
    @Override
    public CompletableFuture<List<DocumentChunk>> processDocument(Path filePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String content = Files.readString(filePath);
                String fileName = filePath.getFileName().toString();
                String md5 = cn.nexon.zerovector.core.util.MD5Util.calculateMD5(filePath);
                String documentId = generateDocumentId(filePath, md5);
                
                return processDocumentInternal(documentId, content, fileName, md5);
            } catch (IOException e) {
                throw new DocumentProcessingException(filePath.toString(), "read file", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<List<DocumentChunk>> processDocument(InputStream inputStream, String fileName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = inputStream.readAllBytes();
                String content = new String(bytes);
                String md5 = cn.nexon.zerovector.core.util.MD5Util.calculateMD5(new java.io.ByteArrayInputStream(bytes));
                String documentId = generateDocumentId(fileName, md5);
                
                return processDocumentInternal(documentId, content, fileName, md5);
            } catch (IOException e) {
                throw new DocumentProcessingException(fileName, "read input stream", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<List<DocumentChunk>> processDocument(String content, String fileName) {
        return CompletableFuture.supplyAsync(() -> {
            String md5 = cn.nexon.zerovector.core.util.MD5Util.calculateMD5(content);
            String documentId = generateDocumentId(fileName, md5);
            return processDocumentInternal(documentId, content, fileName, md5);
        });
    }
    
    @Override
    public void setConfig(ProcessingConfig config) {
        this.config = config;
    }
    
    @Override
    public ProcessingConfig getConfig() {
        return config;
    }
    
    @Override
    public String getVersion() {
        return "1.0.0";
    }
    
    protected List<DocumentChunk> processDocumentInternal(String documentId, String content, String fileName, String md5) {
        List<DocumentChunk> chunks = new ArrayList<>();
        
        if (config.disableChunking()) {
            chunks.add(createChunk(documentId, 0, content, fileName, md5));
            return chunks;
        }
        
        return processWithChunking(documentId, content, fileName, md5);
    }
    
    protected abstract List<DocumentChunk> processWithChunking(String documentId, String content, String fileName, String md5);
    
    protected List<DocumentChunk> splitByParagraphs(String documentId, String content, String fileName, String md5) {
        List<DocumentChunk> chunks = new ArrayList<>();
        
        String[] paragraphs = content.split("\\n\\s*\\n");
        
        for (int i = 0; i < paragraphs.length; i++) {
            String paragraph = paragraphs[i].trim();
            if (!paragraph.isEmpty()) {
                if (paragraph.length() > config.maxChunkSize()) {
                    chunks.addAll(splitLongParagraph(documentId, paragraph, i, fileName, md5));
                } else {
                    chunks.add(createChunk(documentId, i, paragraph, fileName + " - 段落" + (i + 1), md5));
                }
            }
        }
        
        return chunks;
    }
    
    protected List<DocumentChunk> splitLongParagraph(String documentId, String paragraph, int paragraphIndex, String fileName, String md5) {
        List<DocumentChunk> chunks = new ArrayList<>();
        
        int startPos = 0;
        int chunkIndex = 0;
        int globalChunkIndex = paragraphIndex * PARAGRAPH_CHUNK_INDEX_BASE;
        
        while (startPos < paragraph.length()) {
            int endPos = Math.min(startPos + config.maxChunkSize(), paragraph.length());
            
            if (endPos < paragraph.length()) {
                int sentenceEnd = findLastSentenceEnd(paragraph, startPos, endPos);
                if (sentenceEnd > startPos) {
                    endPos = sentenceEnd + 1;
                }
            }
            
            String chunkContent = paragraph.substring(startPos, endPos).trim();
            if (!chunkContent.isEmpty()) {
                chunks.add(createChunk(
                    documentId, 
                    globalChunkIndex + chunkIndex++, 
                    chunkContent, 
                    fileName + " - 段落" + (paragraphIndex + 1) + " - 部分" + (chunkIndex),
                    md5
                ));
            }
            
            startPos = Math.max(endPos, startPos + config.maxChunkSize() - config.maxChunkSizeOverlap());
        }
        
        return chunks;
    }
    
    protected List<DocumentChunk> splitBySentences(String documentId, String content, String fileName, String md5) {
        List<DocumentChunk> chunks = new ArrayList<>();
        
        Pattern sentencePattern = Pattern.compile("[.!?]+\\s+");
        String[] sentences = sentencePattern.split(content);
        
        StringBuilder chunkBuilder = new StringBuilder();
        int chunkIndex = 0;
        
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) {
                continue;
            }
            
            if (chunkBuilder.length() + sentence.length() > config.maxChunkSize() && chunkBuilder.length() > 0) {
                chunks.add(createChunk(documentId, chunkIndex++, chunkBuilder.toString(), fileName + " - 句子组" + chunkIndex, md5));
                chunkBuilder.setLength(0);
            }
            
            chunkBuilder.append(sentence).append(". ");
        }
        
        if (chunkBuilder.length() > 0) {
            chunks.add(createChunk(documentId, chunkIndex, chunkBuilder.toString(), fileName + " - 句子组" + (chunkIndex + 1), md5));
        }
        
        return chunks;
    }
    
    protected List<DocumentChunk> splitBySize(String documentId, String content, String fileName, String md5) {
        List<DocumentChunk> chunks = new ArrayList<>();
        
        int startPos = 0;
        int chunkIndex = 0;
        
        while (startPos < content.length()) {
            int endPos = Math.min(startPos + config.maxChunkSize(), content.length());
            
            if (endPos < content.length()) {
                int sentenceEnd = findLastSentenceEnd(content, startPos, endPos);
                if (sentenceEnd > startPos) {
                    endPos = sentenceEnd + 1;
                }
            }
            
            String chunkContent = content.substring(startPos, endPos).trim();
            if (!chunkContent.isEmpty()) {
                chunks.add(createChunk(documentId, chunkIndex++, chunkContent, fileName + " - 部分" + chunkIndex, md5));
            }
            
            startPos = Math.max(endPos, startPos + config.maxChunkSize() - config.maxChunkSizeOverlap());
        }
        
        return chunks;
    }
    
    protected int findLastSentenceEnd(String text, int startPos, int endPos) {
        int lastSentenceEnd = -1;
        
        for (int i = startPos; i < endPos; i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                if (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1))) {
                    lastSentenceEnd = i;
                }
            }
        }
        
        return lastSentenceEnd;
    }
    
    protected DocumentChunk createChunk(String documentId, int index, String content, String title, String md5) {
        String summary = content.length() > SUMMARY_LENGTH ? 
            content.substring(0, SUMMARY_LENGTH) + "..." : 
            content;
        
        Map<String, Object> metadata = Map.of(
            "documentId", documentId,
            "chunkIndex", index,
            "title", title
        );
        
        return new DocumentChunk(
            documentId + "_" + index,
            content,
            summary,
            null,
            md5,
            metadata
        );
    }
    
    protected String generateDocumentId(Object source, String md5) {
        if (md5 != null && !md5.isEmpty()) {
            return "doc_" + md5.substring(0, 8);
        }
        
        if (source instanceof Path) {
            Path path = (Path) source;
            return path.getFileName().toString().replaceAll("\\.[^.]+$", "") + "_" + 
                   Objects.hash(path.toString());
        } else if (source instanceof String) {
            String fileName = (String) source;
            return fileName.replaceAll("\\.[^.]+$", "") + "_" + 
                   Objects.hash(fileName + System.currentTimeMillis());
        } else {
            return "doc_" + java.util.UUID.randomUUID().toString().replace("-", "");
        }
    }
}
