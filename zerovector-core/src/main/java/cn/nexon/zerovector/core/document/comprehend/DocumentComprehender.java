/*
 * Copyright 2025 nexonlab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.nexon.zerovector.core.document.comprehend;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMPromptTemplates;
import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.core.exception.DocumentProcessingException;
import cn.nexon.zerovector.core.hook.DefaultHookExecutor;
import cn.nexon.zerovector.core.hook.HookContext;
import cn.nexon.zerovector.core.hook.HookExecutor;
import cn.nexon.zerovector.core.hook.HookType;
import cn.nexon.zerovector.core.model.Document;
import cn.nexon.zerovector.core.model.KeywordDefinition;
import cn.nexon.zerovector.core.util.FileUtils;
import cn.nexon.zerovector.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 文档理解器：把正文分块交给 LLM，提取摘要、关键词、实体和示例问题。
 *
 * <p>正文只在这里被读取一次；调用方拿到理解结果后把正文写入
 * {@code ChunkStorage}，语义树仅保存摘要等元数据。
 */
public class DocumentComprehender {

    private static final Logger logger = LoggerFactory.getLogger(DocumentComprehender.class);
    private static final int DEFAULT_CHUNK_CHARS = 4000;

    private final LLMProvider llmProvider;
    private final HookExecutor hookExecutor;
    private final int maxChunkChars;

    public DocumentComprehender(LLMProvider llmProvider, int maxChunkChars) {
        this(llmProvider, maxChunkChars, new DefaultHookExecutor());
    }

    public DocumentComprehender(LLMProvider llmProvider, int maxChunkChars, HookExecutor hookExecutor) {
        this.llmProvider = Objects.requireNonNull(llmProvider, "llmProvider");
        this.maxChunkChars = maxChunkChars > 0 ? maxChunkChars : DEFAULT_CHUNK_CHARS;
        this.hookExecutor = Objects.requireNonNullElseGet(hookExecutor, DefaultHookExecutor::new);
    }

    public int getMaxChunkChars() {
        return maxChunkChars;
    }

    /**
     * 理解文档。
     *
     * @param document 文档；内容为空时读取 {@code filePath}（自动检测编码）
     * @return 理解结果
     */
    public DocumentComprehendResult comprehend(Document document) {
        long startTime = System.currentTimeMillis();
        LLMUsageStats stats = new LLMUsageStats();

        trigger(HookType.DOCUMENT_COMPREHEND_START,
                HookContext.builder(HookType.DOCUMENT_COMPREHEND_START)
                        .data("documentId", document.id())
                        .data("documentTitle", document.title()));

        try {
            String content = resolveContent(document);
            DocumentComprehendResult result = content.isBlank()
                    ? DocumentComprehendResult.of("", List.of())
                    : comprehendContent(document, content, stats);

            trigger(HookType.DOCUMENT_COMPREHEND_END,
                    HookContext.builder(HookType.DOCUMENT_COMPREHEND_END)
                            .data("documentId", document.id())
                            .data("documentTitle", document.title())
                            .data("keywordCount", result.keywordDefinitions().size())
                            .data("llmUsageStats", stats)
                            .durationMillis(System.currentTimeMillis() - startTime));
            return result;
        } catch (RuntimeException e) {
            trigger(HookType.DOCUMENT_COMPREHEND_ERROR,
                    HookContext.builder(HookType.DOCUMENT_COMPREHEND_ERROR)
                            .data("documentId", document.id())
                            .data("documentTitle", document.title())
                            .data("llmUsageStats", stats)
                            .durationMillis(System.currentTimeMillis() - startTime)
                            .error(e));
            throw e;
        }
    }

    private String resolveContent(Document document) {
        if (document.content() != null) {
            return document.content();
        }
        if (document.filePath() == null || document.filePath().isBlank()) {
            return "";
        }
        try {
            return FileUtils.readFileToString(Paths.get(document.filePath()));
        } catch (IOException e) {
            throw new DocumentProcessingException(document.id(), "readFile", e);
        }
    }

    private DocumentComprehendResult comprehendContent(Document document, String content, LLMUsageStats stats) {
        List<KeywordDefinition> allKeywords = new ArrayList<>();
        Set<String> entities = new LinkedHashSet<>();
        Set<String> questions = new LinkedHashSet<>();
        StringBuilder accumulatedSummary = new StringBuilder();

        List<String> chunks = split(content);
        for (int i = 0; i < chunks.size(); i++) {
            String chunkSummary = comprehendChunk(document, chunks.get(i), accumulatedSummary.toString(),
                    allKeywords, entities, questions, i + 1, chunks.size(), stats);
            if (!chunkSummary.isBlank()) {
                accumulatedSummary.append(chunkSummary).append(' ');
            }
        }

        String summary = chunks.size() <= 1
                ? accumulatedSummary.toString().trim()
                : generateFinalSummary(document.title(), accumulatedSummary.toString(), stats);

        return new DocumentComprehendResult(summary,
                distinctKeywords(allKeywords),
                List.copyOf(entities),
                List.copyOf(questions),
                stats);
    }

    private List<String> split(String content) {
        List<String> chunks = new ArrayList<>();
        int position = 0;
        while (position < content.length()) {
            int end = Math.min(position + maxChunkChars, content.length());
            if (end < content.length()) {
                // 不要切断代理对
                if (Character.isHighSurrogate(content.charAt(end - 1))) {
                    end--;
                }
                // 尽量在换行处切分，保留段落完整
                int newline = content.lastIndexOf('\n', end - 1);
                if (newline > position + maxChunkChars / 2) {
                    end = newline + 1;
                }
            }
            if (end <= position) {
                end = Math.min(position + maxChunkChars, content.length());
            }
            chunks.add(content.substring(position, end));
            position = end;
        }
        return chunks;
    }

    private String comprehendChunk(Document document, String chunk, String previousSummary,
                                   List<KeywordDefinition> previousKeywords, Set<String> entities,
                                   Set<String> questions, int index, int total, LLMUsageStats stats) {
        String context = buildContext(previousSummary, previousKeywords);
        String prompt = LLMPromptTemplates.comprehendChunk(context, chunk, index, total);

        LLMResponse response = llmProvider.chat(prompt, SmartCacheStrategy.RequestType.COMPREHEND_CHUNK);
        stats.add(response);
        requireSuccess(response, document.id(), "comprehendChunk");

        ChunkComprehension parsed = JsonUtils.parseJson(response.content(), ChunkComprehension.class);
        if (parsed == null) {
            return "";
        }
        if (parsed.keywordDefinitions() != null) {
            for (KeywordDraft draft : parsed.keywordDefinitions()) {
                if (draft != null && draft.keyword() != null && !draft.keyword().isBlank()) {
                    previousKeywords.add(KeywordDefinition.of(
                            draft.keyword(), draft.definition(), draft.context(), document.id()));
                }
            }
        }
        addNonBlank(entities, parsed.entities());
        addNonBlank(questions, parsed.exampleQuestions());
        return parsed.summary() == null ? "" : parsed.summary().trim();
    }

    private String generateFinalSummary(String title, String accumulatedSummary, LLMUsageStats stats) {
        String summaryInput = accumulatedSummary.length() > 20_000
                ? accumulatedSummary.substring(0, 10_000) + " ... "
                    + accumulatedSummary.substring(accumulatedSummary.length() - 10_000)
                : accumulatedSummary;
        String prompt = LLMPromptTemplates.generateSummary(title, summaryInput);
        LLMResponse response = llmProvider.chat(prompt, SmartCacheStrategy.RequestType.GENERATE_SUMMARY);
        stats.add(response);
        if (response == null || !response.success() || response.content() == null || response.content().isBlank()) {
            logger.warn("最终摘要生成失败，回退到分块摘要: {}",
                    response == null ? "null response" : response.errorMessage());
            return accumulatedSummary.trim();
        }
        return response.content().trim();
    }

    private String buildContext(String previousSummary, List<KeywordDefinition> previousKeywords) {
        StringBuilder context = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            // 只带最近一段摘要，避免长文档把上下文越滚越大
            String rolling = previousSummary.length() > 1000
                    ? previousSummary.substring(previousSummary.length() - 1000)
                    : previousSummary;
            context.append("前文摘要：").append(rolling).append("\n\n");
        }
        if (!previousKeywords.isEmpty()) {
            String joined = previousKeywords.stream()
                    .limit(5)
                    .map(keyword -> keyword.keyword() + "（" + keyword.definition() + "）")
                    .collect(java.util.stream.Collectors.joining("、"));
            context.append("前文关键词：").append(joined);
        }
        return context.toString();
    }

    private static void addNonBlank(Set<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                target.add(value.trim());
            }
        }
    }

    private List<KeywordDefinition> distinctKeywords(List<KeywordDefinition> keywords) {
        Set<String> seen = new LinkedHashSet<>();
        List<KeywordDefinition> result = new ArrayList<>();
        for (KeywordDefinition keyword : keywords) {
            if (seen.add(keyword.normalizedKeyword())) {
                result.add(keyword);
            }
        }
        return result;
    }

    private void requireSuccess(LLMResponse response, String documentId, String operation) {
        if (response == null || !response.success()) {
            String message = response == null ? "null response" : response.errorMessage();
            throw new DocumentProcessingException(documentId, operation, "LLM call failed: " + message);
        }
    }

    private void trigger(HookType type, HookContext.Builder builder) {
        try {
            hookExecutor.executeHooks(type, builder);
        } catch (RuntimeException e) {
            logger.warn("Hook 执行失败: {}", type, e);
        }
    }

    /** LLM 返回的 JSON 结构。 */
    private record ChunkComprehension(
            String summary,
            List<KeywordDraft> keywordDefinitions,
            List<String> entities,
            List<String> exampleQuestions
    ) {
    }

    private record KeywordDraft(String keyword, String definition, String context) {
    }
}
