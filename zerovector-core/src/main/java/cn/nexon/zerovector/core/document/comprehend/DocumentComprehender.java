package cn.nexon.zerovector.core.document.comprehend;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.model.Document;
import cn.nexon.zerovector.core.model.KeywordDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        if (document.isFileBased()) {
            return comprehendFileBased(document);
        } else {
            return comprehendContentBased(document);
        }
    }

    private DocumentComprehendResult comprehendFileBased(Document document) {
        String filePath = document.filePath();
        long fileSize;
        try {
            fileSize = Files.size(Paths.get(filePath));
        } catch (IOException e) {
            logger.error("无法获取文件大小: {}", filePath, e);
            return DocumentComprehendResult.of("", List.of());
        }

        if (fileSize == 0) {
            logger.warn("文件为空: {}", filePath);
            return DocumentComprehendResult.of("", List.of());
        }

        logger.info("开始理解文档: {}, 大小: {} bytes", document.title(), fileSize);

        List<KeywordDefinition> allKeywordDefinitions = new ArrayList<>();
        List<String> allEntities = new ArrayList<>();
        List<String> allQuestions = new ArrayList<>();
        StringBuilder accumulatedSummary = new StringBuilder();

        if (fileSize < MMAP_THRESHOLD) {
            String content = readSmallFile(filePath);
            return processContent(content, document);
        }

        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r");
                FileChannel channel = raf.getChannel()) {

            long position = 0;
            int chunkIndex = 0;
            int totalChunks = (int) ((fileSize + maxChunkSize - 1) / maxChunkSize);

            while (position < fileSize) {
                long remaining = fileSize - position;
                int chunkSize = (int) Math.min(maxChunkSize, remaining);
                long mapSize = Math.min(chunkSize, Integer.MAX_VALUE);

                MappedByteBuffer buffer = channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        position,
                        mapSize);

                try {
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    String chunk = new String(bytes, StandardCharsets.UTF_8);

                    String context = buildContext(accumulatedSummary.toString(), allKeywordDefinitions, chunk);
                    String prompt = buildComprehendPrompt(context, chunkIndex + 1, totalChunks);
                    String fullPrompt = prompt + "\n\n" + chunk;

                    String response = llmProvider.comprehendChunk(fullPrompt);
                    DocumentComprehendResult chunkResult;
                    try {
                        chunkResult = parseComprehendResponse(response);
                    } catch (Exception e) {
                        logger.error("解析LLM响应失败: {}", e.getMessage());
                        chunkResult = new DocumentComprehendResult("", List.of(), List.of(), List.of());
                    }

                    allKeywordDefinitions.addAll(chunkResult.keywordDefinitions());
                    allEntities.addAll(chunkResult.entities());
                    allQuestions.addAll(chunkResult.exampleQuestions());

                    if (chunkResult.summary() != null && !chunkResult.summary().isEmpty()) {
                        accumulatedSummary.append(chunkResult.summary()).append(" ");
                    }

                    position += chunkSize;
                    chunkIndex++;

                    logger.debug("处理文档块 {}/{}, 位置: {}/{}, 大小: {} bytes",
                            chunkIndex, totalChunks, position, chunkSize);
                } finally {
                    unmapBuffer(buffer);
                }
            }

            String summaryPrompt = "请为以下文档生成摘要（不超过200字）：\n\n" +
                    "标题：" + document.title() + "\n" +
                    "内容：" + accumulatedSummary.toString();
            String finalSummary = llmProvider.generateSummary(summaryPrompt);

            logger.info("文档理解完成: {}, 提取 {} 个关键词", document.title(), allKeywordDefinitions.size());

            return new DocumentComprehendResult(
                    finalSummary,
                    allKeywordDefinitions.stream().distinct().toList(),
                    allEntities.stream().distinct().toList(),
                    allQuestions.stream().distinct().toList());
        } catch (IOException e) {
            logger.error("读取文档失败: {}", filePath, e);
            return DocumentComprehendResult.of("", List.of());
        }
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

            String context = buildContext(accumulatedSummary.toString(), allKeywordDefinitions, chunk);
            String prompt = buildComprehendPrompt(context, chunkIndex + 1, totalChunks);
            String fullPrompt = prompt + "\n\n" + chunk;

            String response = llmProvider.comprehendChunk(fullPrompt);
            DocumentComprehendResult chunkResult;
            try {
                chunkResult = parseComprehendResponse(response);
            } catch (Exception e) {
                logger.error("解析LLM响应失败: {}", e.getMessage());
                chunkResult = new DocumentComprehendResult("", List.of(), List.of(), List.of());
            }

            allKeywordDefinitions.addAll(chunkResult.keywordDefinitions());
            allEntities.addAll(chunkResult.entities());
            allQuestions.addAll(chunkResult.exampleQuestions());

            if (chunkResult.summary() != null && !chunkResult.summary().isEmpty()) {
                accumulatedSummary.append(chunkResult.summary()).append(" ");
            }

            position = end;
            chunkIndex++;

            logger.debug("处理文档块 {}/{}, 位置: {}/{}", chunkIndex, totalChunks, position, totalLength);
        }

        String summaryPrompt = "请为以下文档生成摘要（不超过200字）：\n\n" +
                "标题：" + document.title() + "\n" +
                "内容：" + accumulatedSummary.toString();
        String finalSummary = llmProvider.generateSummary(summaryPrompt);

        logger.info("文档理解完成: {}, 提取 {} 个关键词", document.title(), allKeywordDefinitions.size());

        return new DocumentComprehendResult(
                finalSummary,
                allKeywordDefinitions.stream().distinct().toList(),
                allEntities.stream().distinct().toList(),
                allQuestions.stream().distinct().toList());
    }

    private String readSmallFile(String filePath) {
        try {
            return Files.readString(Paths.get(filePath));
        } catch (Exception e) {
            logger.error("读取小文件失败: {}", filePath, e);
            return "";
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
        return "请分析以下文档内容（第" + chunkIndex + "部分，共" + totalChunks + "部分）：\n\n" +
                context +
                "\n\n请提取：\n" +
                "1. 这部分的摘要（不超过100字）\n" +
                "2. 这部分的关键词及其定义（5-10个），每个关键词需要包含：\n" +
                "   - keyword: 关键词\n" +
                "   - definition: 关键词的定义\n" +
                "   - context: 关键词出现的上下文（不超过50字）\n" +
                "3. 这部分的实体（5-10个）\n" +
                "4. 这部分的示例问题（1-3个）\n\n" +
                "按以下JSON格式返回：\n" +
                "{\n" +
                "  \"summary\": \"摘要\",\n" +
                "  \"keywordDefinitions\": [\n" +
                "    {\n" +
                "      \"keyword\": \"关键词1\",\n" +
                "      \"definition\": \"定义1\",\n" +
                "      \"context\": \"上下文1\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"keyword\": \"关键词2\",\n" +
                "      \"definition\": \"定义2\",\n" +
                "      \"context\": \"上下文2\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"entities\": [\"实体1\", \"实体2\"],\n" +
                "  \"exampleQuestions\": [\"问题1\", \"问题2\"]\n" +
                "}";
    }

    private DocumentComprehendResult parseComprehendResponse(String jsonContent) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> resultMap = objectMapper.readValue(jsonContent, Map.class);
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
