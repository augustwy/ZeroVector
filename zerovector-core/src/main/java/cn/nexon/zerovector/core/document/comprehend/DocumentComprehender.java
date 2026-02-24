package cn.nexon.zerovector.core.document.comprehend;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMPromptTemplates;
import cn.nexon.zerovector.core.exception.DocumentProcessingException;
import cn.nexon.zerovector.core.exception.PromptLoadException;
import cn.nexon.zerovector.core.model.Document;
import cn.nexon.zerovector.core.model.KeywordDefinition;
import cn.nexon.zerovector.core.util.FileUtils;
import cn.nexon.zerovector.core.util.JsonUtils;
import cn.nexon.zerovector.core.util.PerformanceMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class DocumentComprehender {
    private static final Logger logger = LoggerFactory.getLogger(DocumentComprehender.class);

    private final LLMProvider llmProvider;
    private final int maxChunkSize;
    private static final int MMAP_THRESHOLD = 10 * 1024 * 1024;

    public DocumentComprehender(LLMProvider llmProvider, int maxChunkSize) {
        this.llmProvider = llmProvider;
        this.maxChunkSize = maxChunkSize;
    }

    public DocumentComprehendResult comprehend(Document document) {
        PerformanceMonitor totalMonitor = new PerformanceMonitor("DocumentComprehender.comprehend");
        totalMonitor.start();
        
        try {
            if (document.isFileBased()) {
                return comprehendFileBased(document);
            } else {
                return comprehendContentBased(document);
            }
        } finally {
            totalMonitor.stop();
            logger.info("文档理解总耗时: {}ms, 文档: {}", totalMonitor.getDurationMillis(), document.title());
        }
    }

    private DocumentComprehendResult comprehendFileBased(Document document) {
        PerformanceMonitor fileMonitor = new PerformanceMonitor("DocumentComprehender.comprehendFileBased");
        fileMonitor.start();
        
        String filePath = document.filePath();
        long fileSize = getFileSize(filePath);

        if (fileSize == 0) {
            logger.warn("文件为空: {}", filePath);
            return DocumentComprehendResult.of("", List.of());
        }

        logger.info("开始理解文档: {}, 大小: {} bytes", document.title(), fileSize);

        if (fileSize < MMAP_THRESHOLD) {
            DocumentComprehendResult result = comprehendSmallFile(filePath, document);
            fileMonitor.stop();
            logger.info("文件文档理解完成: {}ms", fileMonitor.getDurationMillis());
            return result;
        }

        DocumentComprehendResult result = comprehendLargeFile(filePath, document, fileSize);
        fileMonitor.stop();
        logger.info("大文件文档理解完成: {}ms", fileMonitor.getDurationMillis());
        return result;
    }
    
    private long getFileSize(String filePath) {
        try {
            return FileUtils.getFileSize(filePath);
        } catch (IOException e) {
            throw new DocumentProcessingException("unknown", "getFileSize", e);
        }
    }
    
    private DocumentComprehendResult comprehendSmallFile(String filePath, Document document) {
        String content = readSmallFile(filePath);
        return processContent(content, document);
    }
    
    private DocumentComprehendResult comprehendLargeFile(String filePath, Document document, long fileSize) {
        List<KeywordDefinition> allKeywordDefinitions = new ArrayList<>();
        List<String> allEntities = new ArrayList<>();
        List<String> allQuestions = new ArrayList<>();
        StringBuilder accumulatedSummary = new StringBuilder();

        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r");
                FileChannel channel = raf.getChannel()) {

            long position = 0;
            int chunkIndex = 0;
            int totalChunks = (int) ((fileSize + maxChunkSize - 1) / maxChunkSize);

            while (position < fileSize) {
                ChunkProcessingResult chunkResult = processFileChunk(channel, position, fileSize, accumulatedSummary, allKeywordDefinitions, allEntities, allQuestions, chunkIndex, totalChunks);
                
                accumulatedSummary.append(chunkResult.summary()).append(" ");
                position += chunkResult.chunkSize();
                chunkIndex++;
                
                logger.debug("处理文档块 {}/{}, 耗时: {}ms, LLM耗时: {}ms, 位置: {}/{}",
                        chunkIndex, totalChunks, chunkResult.processingTime(), chunkResult.llmTime(), position, chunkResult.chunkSize());
            }

            String finalSummary = generateFinalSummary(document.title(), accumulatedSummary.toString());

            logger.info("文档理解完成: {}, 提取 {} 个关键词", document.title(), allKeywordDefinitions.size());

            return new DocumentComprehendResult(
                    finalSummary,
                    allKeywordDefinitions.stream().distinct().toList(),
                    allEntities.stream().distinct().toList(),
                    allQuestions.stream().distinct().toList());
        } catch (IOException e) {
            throw new DocumentProcessingException(document.id(), "readFile", e);
        }
    }
    
    private ChunkProcessingResult processFileChunk(FileChannel channel, long position, long fileSize, 
            StringBuilder accumulatedSummary, List<KeywordDefinition> allKeywordDefinitions, 
            List<String> allEntities, List<String> allQuestions, int chunkIndex, int totalChunks) throws IOException {
        
        PerformanceMonitor chunkMonitor = new PerformanceMonitor("DocumentComprehender.processChunk");
        chunkMonitor.start();
        
        long remaining = fileSize - position;
        int chunkSize = (int) Math.min(maxChunkSize, remaining);
        long mapSize = Math.min(chunkSize, Integer.MAX_VALUE);

        MappedByteBuffer buffer = channel.map(
                FileChannel.MapMode.READ_ONLY,
                position,
                mapSize);

        try {
            String chunk = readMappedBuffer(buffer);
            
            ChunkComprehendResult comprehendResult = comprehendChunkWithLLM(
                chunk, accumulatedSummary.toString(), allKeywordDefinitions, chunkIndex, totalChunks);
            
            allKeywordDefinitions.addAll(comprehendResult.keywordDefinitions());
            allEntities.addAll(comprehendResult.entities());
            allQuestions.addAll(comprehendResult.questions());
            
            chunkMonitor.stop();
            
            return new ChunkProcessingResult(
                comprehendResult.summary(),
                chunkSize,
                chunkMonitor.getDurationMillis(),
                comprehendResult.llmTime()
            );
        } finally {
            unmapBuffer(buffer);
        }
    }
    
    private String readMappedBuffer(MappedByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    
    private ChunkComprehendResult comprehendChunkWithLLM(String chunk, String previousSummary, 
            List<KeywordDefinition> previousKeywords, int chunkIndex, int totalChunks) {
        
        String context = buildContext(previousSummary, previousKeywords, chunk);
        String prompt = buildComprehendPrompt(context, chunkIndex, totalChunks);
        String fullPrompt = prompt + "\n\n" + chunk;

        PerformanceMonitor llmMonitor = new PerformanceMonitor("DocumentComprehender.llmComprehendChunk");
        llmMonitor.start();
        String response = llmProvider.comprehendChunk(fullPrompt);
        llmMonitor.stop();
        
        DocumentComprehendResult chunkResult = parseComprehendResponse(response);
        
        return new ChunkComprehendResult(
            chunkResult.summary(),
            chunkResult.keywordDefinitions(),
            chunkResult.entities(),
            chunkResult.exampleQuestions(),
            llmMonitor.getDurationMillis()
        );
    }
    
    private record ChunkComprehendResult(String summary, List<KeywordDefinition> keywordDefinitions, 
            List<String> entities, List<String> questions, long llmTime) {}
    
    private record ChunkProcessingResult(String summary, int chunkSize, long processingTime, long llmTime) {}
    
    private String generateFinalSummary(String title, String accumulatedSummary) {
        PerformanceMonitor summaryMonitor = new PerformanceMonitor("DocumentComprehender.llmGenerateSummary");
        summaryMonitor.start();
        String summaryPrompt = LLMPromptTemplates.generateSummary(title, accumulatedSummary);
        String finalSummary = llmProvider.generateSummary(summaryPrompt);
        summaryMonitor.stop();
        
        return finalSummary;
    }

    private DocumentComprehendResult comprehendContentBased(Document document) {
        String content = document.content();
        if (content == null || content.isEmpty()) {
            logger.warn("文档内容为空: {}", document.id());
            return DocumentComprehendResult.of("", List.of());
        }

        return processContent(content, document);
    }

    private DocumentComprehendResult processContent(String content, Document document) {
        PerformanceMonitor contentMonitor = new PerformanceMonitor("DocumentComprehender.processContent");
        contentMonitor.start();
        
        logger.info("开始理解文档: {}, 大小: {} chars", document.title(), content.length());

        List<cn.nexon.zerovector.core.model.KeywordDefinition> allKeywordDefinitions = new ArrayList<>();
        List<String> allEntities = new ArrayList<>();
        List<String> allQuestions = new ArrayList<>();
        StringBuilder accumulatedSummary = new StringBuilder();

        int position = 0;
        int totalLength = content.length();
        int chunkIndex = 0;
        int totalChunks = (totalLength + maxChunkSize - 1) / maxChunkSize;

        while (position < totalLength) {
            int end = Math.min(position + maxChunkSize, totalLength);
            String chunk = content.substring(position, end);

            ChunkComprehendResult comprehendResult = comprehendChunkWithLLM(
                chunk, accumulatedSummary.toString(), allKeywordDefinitions, chunkIndex + 1, totalChunks);
            
            allKeywordDefinitions.addAll(comprehendResult.keywordDefinitions());
            allEntities.addAll(comprehendResult.entities());
            allQuestions.addAll(comprehendResult.questions());

            if (comprehendResult.summary() != null && !comprehendResult.summary().isEmpty()) {
                accumulatedSummary.append(comprehendResult.summary()).append(" ");
            }

            position = end;
            chunkIndex++;
            
            logger.debug("处理文档块 {}/{}, 耗时: {}ms, LLM耗时: {}ms, 位置: {}/{}", 
                    chunkIndex, totalChunks, comprehendResult.llmTime(), comprehendResult.llmTime(), position, totalLength);
        }

        String finalSummary = generateFinalSummary(document.title(), accumulatedSummary.toString());

        logger.debug("文档理解完成: {}, 提取 {} 个关键词, 总耗时: {}ms", 
                document.title(), allKeywordDefinitions.size(), contentMonitor.getDurationMillis());

        return new DocumentComprehendResult(
                finalSummary,
                allKeywordDefinitions.stream().distinct().toList(),
                allEntities.stream().distinct().toList(),
                allQuestions.stream().distinct().toList());
    }

    private String readSmallFile(String filePath) {
        try {
            return FileUtils.readFileToString(filePath);
        } catch (Exception e) {
            throw new DocumentProcessingException("unknown", "readSmallFile", e);
        }
    }

    private void unmapBuffer(MappedByteBuffer buffer) {
        if (buffer == null) {
            return;
        }

        try {
            buffer.force();

            try {
                java.lang.reflect.Field cleanerField = buffer.getClass().getDeclaredField("cleaner");
                cleanerField.setAccessible(true);
                Object cleaner = cleanerField.get(buffer);
                if (cleaner != null) {
                    java.lang.reflect.Method cleanMethod = cleaner.getClass().getMethod("clean");
                    cleanMethod.invoke(cleaner);
                }
            } catch (Exception e) {
                logger.warn("释放 mmap buffer 失败", e);
            }
        } catch (Exception e) {
            logger.warn("强制写入 mmap buffer 失败", e);
        }
    }

    private String buildContext(String previousSummary, List<KeywordDefinition> previousKeywords, String currentChunk) {
        StringBuilder context = new StringBuilder();

        if (previousSummary != null && !previousSummary.isEmpty()) {
            context.append("前文摘要：").append(previousSummary).append("\n\n");
        }

        if (!previousKeywords.isEmpty()) {
            context.append("前文关键词：");
            for (int i = 0; i < Math.min(previousKeywords.size(), 5); i++) {
                KeywordDefinition kd = previousKeywords.get(i);
                context.append(kd.keyword()).append("（").append(kd.definition()).append("）");
                if (i < Math.min(previousKeywords.size(), 5) - 1) {
                    context.append("、");
                }
            }
            context.append("\n\n");
        }

        context.append("当前内容：").append(currentChunk);

        return context.toString();
    }

    private String buildComprehendPrompt(String context, int chunkIndex, int totalChunks) {
        return LLMPromptTemplates.comprehendChunk(context, chunkIndex, totalChunks);
    }

    private DocumentComprehendResult parseComprehendResponse(String jsonContent) {
        Map<String, Object> resultMap = JsonUtils.parseToMap(jsonContent);
        String summary = (String) resultMap.get("summary");

        List<Map<String, String>> keywordDataList = (List<Map<String, String>>) resultMap
                .getOrDefault("keywordDefinitions", List.of());
        List<String> entities = (List<String>) resultMap.getOrDefault("entities", List.of());
        List<String> questions = (List<String>) resultMap.getOrDefault("exampleQuestions", List.of());

        List<cn.nexon.zerovector.core.model.KeywordDefinition> keywordDefinitions = new ArrayList<>();
        for (Map<String, String> keywordData : keywordDataList) {
            String keyword = keywordData.get("keyword");
            String definition = keywordData.get("definition");
            String context = keywordData.get("context");
            keywordDefinitions.add(cn.nexon.zerovector.core.model.KeywordDefinition.of(keyword, definition, context,
                    "doc_" + System.currentTimeMillis()));
        }

        return new DocumentComprehendResult(summary, keywordDefinitions, entities, questions);
    }
}
