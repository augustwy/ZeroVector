package cn.nexon.zerovector.core.document.comprehend;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DocumentComprehender {

    private static final Logger logger = LoggerFactory.getLogger(DocumentComprehender.class);

    private final LLMProvider llmProvider;
    private final int chunkSize;

    public DocumentComprehender(LLMProvider llmProvider, int chunkSize) {
        this.llmProvider = llmProvider;
        this.chunkSize = chunkSize;
    }

    public DocumentComprehendResult comprehend(DocumentChunk document) {
        String content = readContent(document);
        if (content == null || content.isEmpty()) {
            return new DocumentComprehendResult("", List.of(), List.of(), List.of());
        }

        List<String> allKeywords = new ArrayList<>();
        List<String> allEntities = new ArrayList<>();
        List<String> allQuestions = new ArrayList<>();
        String accumulatedSummary = "";

        int position = 0;
        int totalLength = content.length();
        int chunkIndex = 0;

        while (position < totalLength) {
            int end = Math.min(position + chunkSize, totalLength);
            String chunk = content.substring(position, end);

            String context = buildContext(accumulatedSummary, allKeywords, chunk);
            String prompt = buildComprehendPrompt(context, chunkIndex + 1);
            DocumentComprehendResult result = llmProvider.comprehendChunk(chunk, prompt);

            allKeywords.addAll(result.keywords());
            allEntities.addAll(result.entities());
            allQuestions.addAll(result.exampleQuestions());

            accumulatedSummary = result.summary();
            position = end;
            chunkIndex++;

            logger.debug("处理文档块 {}/{}, 位置: {}/{}", chunkIndex, position, totalLength);
        }

        String finalSummary = llmProvider.generateSummary(document.id(), accumulatedSummary);

        return new DocumentComprehendResult(
                finalSummary,
                allKeywords.stream().distinct().limit(10).toList(),
                allEntities.stream().distinct().limit(10).toList(),
                allQuestions.stream().distinct().limit(5).toList()
        );
    }

    public DocumentComprehendResult comprehend(Path filePath) {
        try {
            String content = Files.readString(filePath);
            if (content == null || content.isEmpty()) {
                return new DocumentComprehendResult("", List.of(), List.of(), List.of());
            }

            List<String> allKeywords = new ArrayList<>();
            List<String> allEntities = new ArrayList<>();
            List<String> allQuestions = new ArrayList<>();
            String accumulatedSummary = "";

            int position = 0;
            int totalLength = content.length();
            int chunkIndex = 0;

            while (position < totalLength) {
                int end = Math.min(position + chunkSize, totalLength);
                String chunk = content.substring(position, end);

                String context = buildContext(accumulatedSummary, allKeywords, chunk);
                String prompt = buildComprehendPrompt(context, chunkIndex + 1);
                DocumentComprehendResult result = llmProvider.comprehendChunk(chunk, prompt);

                allKeywords.addAll(result.keywords());
                allEntities.addAll(result.entities());
                allQuestions.addAll(result.exampleQuestions());

                accumulatedSummary = result.summary();
                position = end;
                chunkIndex++;

                logger.debug("处理文档块 {}/{}, 位置: {}/{}", chunkIndex, position, totalLength);
            }

            String finalSummary = llmProvider.generateSummary(filePath.getFileName().toString(), accumulatedSummary);

            return new DocumentComprehendResult(
                    finalSummary,
                    allKeywords.stream().distinct().limit(10).toList(),
                    allEntities.stream().distinct().limit(10).toList(),
                    allQuestions.stream().distinct().limit(5).toList()
            );
        } catch (Exception e) {
            logger.error("读取文档失败: {}", filePath, e);
            return new DocumentComprehendResult("", List.of(), List.of(), List.of());
        }
    }

    private String readContent(DocumentChunk document) {
        if (document.isFilePathBased()) {
            try {
                return Files.readString(Path.of(document.filePath()));
            } catch (Exception e) {
                logger.error("读取文档失败: {}", document.filePath(), e);
                return "";
            }
        }
        return document.content();
    }

    private String buildContext(String previousSummary, List<String> previousKeywords, String currentChunk) {
        StringBuilder context = new StringBuilder();
        
        if (previousSummary != null && !previousSummary.isEmpty()) {
            context.append("前文摘要：").append(previousSummary).append("\n\n");
        }
        
        if (!previousKeywords.isEmpty()) {
            context.append("前文关键词：").append(String.join("、", previousKeywords)).append("\n\n");
        }
        
        context.append("当前内容：").append(currentChunk);
        
        return context.toString();
    }

    private String buildComprehendPrompt(String context, int chunkIndex) {
        return "请分析以下文档内容（第" + chunkIndex + "部分）：\n\n" +
                context +
                "\n\n请提取：\n" +
                "1. 这部分的摘要（不超过100字）\n" +
                "2. 这部分的关键词（5-10个）\n" +
                "3. 这部分的实体（5-10个）\n" +
                "4. 这部分的示例问题（1-3个）\n\n" +
                "按以下JSON格式返回：\n" +
                "{\n" +
                "  \"summary\": \"摘要\",\n" +
                "  \"keywords\": [\"关键词1\", \"关键词2\"],\n" +
                "  \"entities\": [\"实体1\", \"实体2\"],\n" +
                "  \"exampleQuestions\": [\"问题1\", \"问题2\"]\n" +
                "}";
    }
}
