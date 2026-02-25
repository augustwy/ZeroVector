package cn.nexon.zerovector.core.document.comprehend;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.ai.LLMPromptTemplates;
import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.exception.DocumentProcessingException;
import cn.nexon.zerovector.core.exception.PromptLoadException;
import cn.nexon.zerovector.core.hook.HookContext;
import cn.nexon.zerovector.core.hook.HookExecutor;
import cn.nexon.zerovector.core.hook.HookType;
import cn.nexon.zerovector.core.hook.DefaultHookExecutor;
import cn.nexon.zerovector.core.model.Document;
import cn.nexon.zerovector.core.model.KeywordDefinition;
import cn.nexon.zerovector.core.util.FileUtils;
import cn.nexon.zerovector.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档理解器
 * 负责解析文档内容，使用 LLM 提取摘要、关键词、实体和示例问题
 */
public class DocumentComprehender {
    private static final Logger logger = LoggerFactory.getLogger(DocumentComprehender.class);

    private final LLMProvider llmProvider;
    private final HookExecutor hookExecutor;
    private final int maxChunkSize;
    private static final int MMAP_THRESHOLD = 10 * 1024 * 1024;

    public DocumentComprehender(LLMProvider llmProvider, int maxChunkSize) {
        this(llmProvider, maxChunkSize, new DefaultHookExecutor());
    }

    public DocumentComprehender(LLMProvider llmProvider, int maxChunkSize, HookExecutor hookExecutor) {
        this.llmProvider = llmProvider;
        this.maxChunkSize = maxChunkSize;
        this.hookExecutor = hookExecutor != null ? hookExecutor : new DefaultHookExecutor();
    }

    /**
     * 理解文档
     * 解析文档内容，提取摘要、关键词、实体和示例问题
     *
     * @param document 待理解的文档
     * @return 文档理解结果，包含 LLM 调用统计数据
     */
    public DocumentComprehendResult comprehend(Document document) {
        long startTime = System.currentTimeMillis();
        LLMUsageStats stats = new LLMUsageStats();
        
        hookExecutor.executeHooks(HookType.DOCUMENT_COMPREHEND_START,
            HookContext.builder(HookType.DOCUMENT_COMPREHEND_START)
                .data("documentId", document.id())
                .data("documentTitle", document.title())
        );
        
        try {
            DocumentComprehendResult result;
            if (document.isFileBased()) {
                result = comprehendFileBased(document, stats);
            } else {
                result = comprehendContentBased(document, stats);
            }
            
            long duration = System.currentTimeMillis() - startTime;
            hookExecutor.executeHooks(HookType.DOCUMENT_COMPREHEND_END,
                HookContext.builder(HookType.DOCUMENT_COMPREHEND_END)
                    .data("documentId", document.id())
                    .data("documentTitle", document.title())
                    .data("keywordCount", result.keywordDefinitions().size())
                    .data("llmUsageStats", stats)
                    .durationMillis(duration)
            );
            
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            hookExecutor.executeHooks(HookType.DOCUMENT_COMPREHEND_ERROR,
                HookContext.builder(HookType.DOCUMENT_COMPREHEND_ERROR)
                    .data("documentId", document.id())
                    .data("documentTitle", document.title())
                    .data("llmUsageStats", stats)
                    .durationMillis(duration)
                    .error(e)
            );
            throw e;
        }
    }

    private DocumentComprehendResult comprehendFileBased(Document document, LLMUsageStats stats) {
        String filePath = document.filePath();
        long fileSize = getFileSize(filePath);

        if (fileSize == 0) {
            logger.warn("文件为空: {}", filePath);
            return DocumentComprehendResult.of("", List.of());
        }

        logger.debug("开始理解文档: {}, 大小: {} bytes", document.title(), fileSize);

        if (fileSize < MMAP_THRESHOLD) {
            DocumentComprehendResult result = comprehendSmallFile(filePath, document, stats);
            logger.debug("文件文档理解完成");
            return result;
        }

        DocumentComprehendResult result = comprehendLargeFile(filePath, document, fileSize, stats);
        logger.debug("大文件文档理解完成");
        return result;
    }
    
    private long getFileSize(String filePath) {
        try {
            return FileUtils.getFileSize(filePath);
        } catch (IOException e) {
            throw new DocumentProcessingException("unknown", "getFileSize", e);
        }
    }
    
    private DocumentComprehendResult comprehendSmallFile(String filePath, Document document, LLMUsageStats stats) {
        String content = readSmallFile(filePath);
        return processContent(content, document, stats);
    }
    
    private DocumentComprehendResult comprehendLargeFile(String filePath, Document document, long fileSize, LLMUsageStats stats) {
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
                ChunkProcessingResult chunkResult = processFileChunk(channel, position, fileSize, accumulatedSummary, allKeywordDefinitions, allEntities, allQuestions, chunkIndex, totalChunks, stats);
                
                accumulatedSummary.append(chunkResult.summary()).append(" ");
                position += chunkResult.chunkSize();
                chunkIndex++;
                
                logger.debug("处理文档块 {}/{}", chunkIndex, totalChunks);
            }

            String finalSummary = generateFinalSummary(document.title(), accumulatedSummary.toString(), stats);

            logger.debug("文档理解完成: {}, 提取 {} 个关键词", document.title(), allKeywordDefinitions.size());

            return new DocumentComprehendResult(
                    finalSummary,
                    allKeywordDefinitions.stream().distinct().toList(),
                    allEntities.stream().distinct().toList(),
                    allQuestions.stream().distinct().toList(),
                    stats);
        } catch (IOException e) {
            throw new DocumentProcessingException(document.id(), "readFile", e);
        }
    }
    
    private ChunkProcessingResult processFileChunk(FileChannel channel, long position, long fileSize, 
            StringBuilder accumulatedSummary, List<KeywordDefinition> allKeywordDefinitions, 
            List<String> allEntities, List<String> allQuestions, int chunkIndex, int totalChunks, LLMUsageStats stats) throws IOException {
        
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
                chunk, accumulatedSummary.toString(), allKeywordDefinitions, chunkIndex, totalChunks, stats);
            
            allKeywordDefinitions.addAll(comprehendResult.keywordDefinitions());
            allEntities.addAll(comprehendResult.entities());
            allQuestions.addAll(comprehendResult.questions());
            
            return new ChunkProcessingResult(
                comprehendResult.summary(),
                chunkSize
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
            List<KeywordDefinition> previousKeywords, int chunkIndex, int totalChunks, LLMUsageStats stats) {
        
        String context = buildContext(previousSummary, previousKeywords, chunk);
        String prompt = buildComprehendPrompt(context, chunkIndex, totalChunks);
        String fullPrompt = prompt + "\n\n" + chunk;

        LLMResponse response = llmProvider.comprehendChunk(fullPrompt);
        stats.add(response);
        
        DocumentComprehendResult chunkResult = parseComprehendResponse(response.content());
        
        return new ChunkComprehendResult(
            chunkResult.summary(),
            chunkResult.keywordDefinitions(),
            chunkResult.entities(),
            chunkResult.exampleQuestions()
        );
    }
    
    private record ChunkComprehendResult(String summary, List<KeywordDefinition> keywordDefinitions, 
            List<String> entities, List<String> questions) {}
    
    private record ChunkProcessingResult(String summary, int chunkSize) {}
    
    private String generateFinalSummary(String title, String accumulatedSummary, LLMUsageStats stats) {
        String summaryPrompt = LLMPromptTemplates.generateSummary(title, accumulatedSummary);
        LLMResponse response = llmProvider.generateSummary(summaryPrompt);
        stats.add(response);
        
        return response.content();
    }

    private DocumentComprehendResult comprehendContentBased(Document document, LLMUsageStats stats) {
        String content = document.content();
        if (content == null || content.isEmpty()) {
            logger.warn("文档内容为空: {}", document.id());
            return DocumentComprehendResult.of("", List.of());
        }

        return processContent(content, document, stats);
    }

    private DocumentComprehendResult processContent(String content, Document document, LLMUsageStats stats) {
        logger.debug("开始理解文档: {}, 大小: {} chars", document.title(), content.length());

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
                chunk, accumulatedSummary.toString(), allKeywordDefinitions, chunkIndex + 1, totalChunks, stats);
            
            allKeywordDefinitions.addAll(comprehendResult.keywordDefinitions());
            allEntities.addAll(comprehendResult.entities());
            allQuestions.addAll(comprehendResult.questions());

            if (comprehendResult.summary() != null && !comprehendResult.summary().isEmpty()) {
                accumulatedSummary.append(comprehendResult.summary()).append(" ");
            }

            position = end;
            chunkIndex++;
            
            logger.debug("处理文档块 {}/{}", chunkIndex, totalChunks);
        }

        String finalSummary = generateFinalSummary(document.title(), accumulatedSummary.toString(), stats);

        logger.info("文档理解完成: {}, 提取 {} 个关键词", document.title(), allKeywordDefinitions.size());

        return new DocumentComprehendResult(
                finalSummary,
                allKeywordDefinitions.stream().distinct().toList(),
                allEntities.stream().distinct().toList(),
                allQuestions.stream().distinct().toList(),
                stats);
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

        return new DocumentComprehendResult(summary, keywordDefinitions, entities, questions, new LLMUsageStats());
    }
}
