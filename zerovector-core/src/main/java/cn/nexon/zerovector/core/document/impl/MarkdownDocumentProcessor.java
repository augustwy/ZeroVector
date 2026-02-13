package cn.nexon.zerovector.core.document.impl;

import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.document.DocumentProcessor;
import cn.nexon.zerovector.core.util.MD5Util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown文档处理器实现
 * 支持按标题分割Markdown文档
 */
public class MarkdownDocumentProcessor implements DocumentProcessor {
    
    private ProcessingConfig config;
    
    public MarkdownDocumentProcessor() {
        this.config = ProcessingConfig.DEFAULT;
    }
    
    public MarkdownDocumentProcessor(ProcessingConfig config) {
        this.config = config;
    }
    
    @Override
    public CompletableFuture<List<DocumentChunk>> processDocument(Path filePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String content = Files.readString(filePath);
                String fileName = filePath.getFileName().toString();
                String md5 = MD5Util.calculateMD5(filePath);
                String documentId = generateDocumentId(filePath, md5);
                
                return processDocumentInternal(documentId, content, fileName, md5);
            } catch (IOException e) {
                throw new RuntimeException("读取文件失败: " + filePath, e);
            }
        });
    }
    
    @Override
    public CompletableFuture<List<DocumentChunk>> processDocument(InputStream inputStream, String fileName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = inputStream.readAllBytes();
                String content = new String(bytes);
                String md5 = MD5Util.calculateMD5(new java.io.ByteArrayInputStream(bytes));
                String documentId = generateDocumentId(fileName, md5);
                
                return processDocumentInternal(documentId, content, fileName, md5);
            } catch (IOException e) {
                throw new RuntimeException("读取输入流失败", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<List<DocumentChunk>> processDocument(String content, String fileName) {
        return CompletableFuture.supplyAsync(() -> {
            String md5 = MD5Util.calculateMD5(content);
            String documentId = generateDocumentId(fileName, md5);
            return processDocumentInternal(documentId, content, fileName, md5);
        });
    }
    
    @Override
    public List<String> getSupportedFormats() {
        return List.of("md", "markdown");
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
    public String getName() {
        return "MarkdownDocumentProcessor";
    }
    
    @Override
    public String getVersion() {
        return "1.0.0";
    }
    
    /**
     * 内部处理方法
     */
    private List<DocumentChunk> processDocumentInternal(String documentId, String content, String fileName, String md5) {
        List<DocumentChunk> chunks = new ArrayList<>();
        
        // 如果禁用分片，整个文档作为一个块
        if (config.disableChunking()) {
            chunks.add(createChunk(documentId, 0, content, fileName, md5));
            return chunks;
        }
        
        // 如果配置了按标题分割，优先使用
        if (config.splitByHeading()) {
            chunks.addAll(splitByHeadings(documentId, content, fileName, md5));
        } else if (config.splitByParagraph()) {
            chunks.addAll(splitByParagraphs(documentId, content, fileName, md5));
        } else if (config.splitBySentence()) {
            chunks.addAll(splitBySentences(documentId, content, fileName, md5));
        } else {
            chunks.addAll(splitBySize(documentId, content, fileName, md5));
        }
        
        return chunks;
    }
    
    /**
     * 按标题分割文档
     */
    private List<DocumentChunk> splitByHeadings(String documentId, String content, String fileName, String md5) {
        List<DocumentChunk> chunks = new ArrayList<>();
        
        Pattern headingPattern = Pattern.compile(config.headingPattern(), Pattern.MULTILINE);
        Matcher matcher = headingPattern.matcher(content);
        
        int lastEnd = 0;
        int sectionIndex = 0;
        
        while (matcher.find()) {
            // 如果有标题之前的内容，创建一个块
            if (matcher.start() > lastEnd) {
                String sectionContent = content.substring(lastEnd, matcher.start()).trim();
                if (!sectionContent.isEmpty()) {
                    chunks.add(createChunk(documentId, sectionIndex++, sectionContent, fileName + " - 引言", md5));
                }
            }
            
            lastEnd = matcher.start();
        }
        
        // 处理最后一个标题后的内容
        if (lastEnd < content.length()) {
            String sectionContent = content.substring(lastEnd).trim();
            if (!sectionContent.isEmpty()) {
                chunks.add(createChunk(documentId, sectionIndex, sectionContent, fileName + " - 结尾", md5));
            }
        }
        
        return chunks;
    }
    
    /**
     * 按段落分割文档
     */
    private List<DocumentChunk> splitByParagraphs(String documentId, String content, String fileName, String md5) {
        List<DocumentChunk> chunks = new ArrayList<>();
        
        String[] paragraphs = content.split("\\n\\s*\\n");
        
        for (int i = 0; i < paragraphs.length; i++) {
            String paragraph = paragraphs[i].trim();
            if (!paragraph.isEmpty()) {
                // 如果段落太长，进一步分割
                if (paragraph.length() > config.maxChunkSize()) {
                    chunks.addAll(splitLongParagraph(documentId, paragraph, i, fileName, md5));
                } else {
                    chunks.add(createChunk(documentId, i, paragraph, fileName + " - 段落" + (i + 1), md5));
                }
            }
        }
        
        return chunks;
    }
    
    /**
     * 分割过长的段落
     */
    private List<DocumentChunk> splitLongParagraph(String documentId, String paragraph, int paragraphIndex, String fileName, String md5) {
        List<DocumentChunk> chunks = new ArrayList<>();
        
        int startPos = 0;
        int chunkIndex = 0;
        int globalChunkIndex = paragraphIndex * 1000; // 确保每个段落的块索引是唯一的
        
        while (startPos < paragraph.length()) {
            int endPos = Math.min(startPos + config.maxChunkSize(), paragraph.length());
            
            // 尝试在句子边界分割
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
            
            // 计算下一个块的起始位置（考虑重叠）
            startPos = Math.max(endPos, startPos + config.maxChunkSize() - config.maxChunkSizeOverlap());
        }
        
        return chunks;
    }
    
    /**
     * 按句子分割文档
     */
    private List<DocumentChunk> splitBySentences(String documentId, String content, String fileName, String md5) {
        List<DocumentChunk> chunks = new ArrayList<>();
        
        // 使用正则表达式分割句子
        Pattern sentencePattern = Pattern.compile("[.!?]+\\s+");
        String[] sentences = sentencePattern.split(content);
        
        StringBuilder chunkBuilder = new StringBuilder();
        int chunkIndex = 0;
        
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) {
                continue;
            }
            
            // 如果添加这个句子会超过块大小，先保存当前块
            if (chunkBuilder.length() + sentence.length() > config.maxChunkSize() && chunkBuilder.length() > 0) {
                chunks.add(createChunk(documentId, chunkIndex++, chunkBuilder.toString(), fileName + " - 句子组" + chunkIndex, md5));
                chunkBuilder.setLength(0);
            }
            
            chunkBuilder.append(sentence).append(". "); // 添加句号和空格
        }
        
        // 添加最后一个块
        if (chunkBuilder.length() > 0) {
            chunks.add(createChunk(documentId, chunkIndex, chunkBuilder.toString(), fileName + " - 句子组" + (chunkIndex + 1), md5));
        }
        
        return chunks;
    }
    
    /**
     * 按固定大小分割文档
     */
    private List<DocumentChunk> splitBySize(String documentId, String content, String fileName, String md5) {
        List<DocumentChunk> chunks = new ArrayList<>();
        
        int startPos = 0;
        int chunkIndex = 0;
        
        while (startPos < content.length()) {
            int endPos = Math.min(startPos + config.maxChunkSize(), content.length());
            
            // 尝试在句子边界分割
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
            
            // 计算下一个块的起始位置（考虑重叠）
            startPos = Math.max(endPos, startPos + config.maxChunkSize() - config.maxChunkSizeOverlap());
        }
        
        return chunks;
    }
    
    /**
     * 查找最后一个句子结束位置
     */
    private int findLastSentenceEnd(String text, int startPos, int endPos) {
        int lastSentenceEnd = -1;
        
        for (int i = startPos; i < endPos; i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                // 检查是否是句子结束（后面是空格或行结束）
                if (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1))) {
                    lastSentenceEnd = i;
                }
            }
        }
        
        return lastSentenceEnd;
    }
    
    /**
     * 创建文档块
     */
    private DocumentChunk createChunk(String documentId, int index, String content, String title, String md5) {
        // 生成摘要（取前100个字符）
        String summary = content.length() > 100 ? 
            content.substring(0, 100) + "..." : 
            content;
        
        // 创建元数据
        Map<String, Object> metadata = Map.of(
            "documentId", documentId,
            "chunkIndex", index,
            "title", title
        );
        
        return new DocumentChunk(
            documentId + "_" + index,
            content,
            summary,
            null,  // filePath
            md5,   // md5
            metadata
        );
    }
    
    /**
     * 生成文档ID
     */
    private String generateDocumentId(Object source, String md5) {
        // 使用MD5值作为ID的一部分，确保相同内容的文档有相同的ID
        if (md5 != null && !md5.isEmpty()) {
            return "doc_" + md5.substring(0, 8); // 使用MD5前8位作为ID
        }
        
        // 如果没有MD5，回退到原来的方式
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