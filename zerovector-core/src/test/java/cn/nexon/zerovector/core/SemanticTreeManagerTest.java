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

package cn.nexon.zerovector.core;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehender;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.DocumentUploadResult;
import cn.nexon.zerovector.core.model.NavigationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端验证：正文写入 mmap、树只存元数据、重启后仍可检索。
 */
class SemanticTreeManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void addNavigateAndRestart_keepsContentRetrievable() throws Exception {
        Path alpha = write("alpha.md", "alpha document content");
        Path beta = write("beta.md", "beta document content");
        Path kbPath = tempDir.resolve("kb");

        try (SemanticTreeManager manager = manager(kbPath)) {
            List<DocumentUploadResult> results = manager.addDocuments(List.of(alpha, beta));
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(DocumentUploadResult::success));
            assertEquals(2, manager.getSemanticTree().chunks().size());

            NavigationResult result = manager.navigate("alpha");
            assertEquals(1, result.documents().size());
            assertEquals("alpha document content", content(manager, result));

            // 正文不得重复写进树文件
            Path treeMetadata = kbPath.resolve("semantic-tree.d").resolve("metadata.json");
            assertTrue(Files.exists(treeMetadata));
            try (var files = Files.list(kbPath.resolve("semantic-tree.d"))) {
                assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("chunk_")));
            }

            // COPY 模式必须与用户原文件隔离：删除原文件后重启仍可读
            Files.delete(alpha);
            Files.delete(beta);
        }

        // 重启：字典、树、mmap 索引都应可恢复
        try (SemanticTreeManager manager = manager(kbPath)) {
            assertTrue(manager.isTreeLoaded());
            NavigationResult result = manager.navigate("alpha");
            assertEquals(1, result.documents().size());
            assertEquals("alpha document content", content(manager, result));

            // 重启后追加：不能覆盖旧数据
            Path gamma = write("gamma.md", "gamma document content");
            DocumentUploadResult added = manager.addDocument(gamma);
            assertTrue(added.success());

            NavigationResult gammaResult = manager.navigate("gamma");
            assertEquals(1, gammaResult.documents().size());
            assertEquals("gamma document content", content(manager, gammaResult));

            NavigationResult alphaAgain = manager.navigate("alpha");
            assertEquals("alpha document content", content(manager, alphaAgain));
        }
    }

    @Test
    void rebuildingTree_clearsStaleKeywords() throws Exception {
        Path alpha = write("alpha-rebuild.md", "alpha document content");
        try (SemanticTreeManager manager = manager(tempDir.resolve("kb4"))) {
            DocumentUploadResult first = manager.addDocument(alpha);
            assertTrue(first.success());
            assertFalse(manager.getKeywordDictionary().getNodesForKeyword("alpha").isEmpty());

            manager.buildTree(List.of(cn.nexon.zerovector.core.model.Document.fromContent(
                    "new-doc", "new", "gamma document content", Map.of())));

            assertTrue(manager.getKeywordDictionary().getNodesForKeyword("alpha").isEmpty());
            assertFalse(manager.getChunkStorage().exists(first.documentId() + "#0"));
            assertEquals(1, manager.getSemanticTree().chunks().size());
            NavigationResult result = manager.navigate("gamma");
            assertEquals(1, result.documents().size());
        }
    }

    @Test
    void asyncUploadWithSingleConcurrencyPermit_doesNotDeadlock() throws Exception {
        Path file = write("async.md", "alpha async content");
        ConcurrencyProperties props = new ConcurrencyProperties();
        props.setMaxConcurrentRequests(1);
        LLMProvider llm = new ScriptedLLM();
        try (SemanticTreeManager manager = new SemanticTreeManager(
                llm, new DocumentComprehender(llm, 4000, null), tempDir.resolve("kb-async"),
                10, props, null)) {
            DocumentUploadResult result = manager.addDocumentAsync(file)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(result.success());
            assertEquals("alpha async content",
                    content(manager, manager.navigate("alpha")));
        }
    }

    @Test
    void markdownChaptersBecomeSeparateLeavesAndOnlyMatchedSectionIsRead() throws Exception {
        String content = "# Alpha\n alpha body\n\n# Beta\n beta body\n";
        Path file = write("chapters.md", content);
        try (SemanticTreeManager manager = manager(tempDir.resolve("kb-chapters"))) {
            assertTrue(manager.addDocument(file,
                    cn.nexon.zerovector.core.model.SourceMode.COPY,
                    cn.nexon.zerovector.core.model.ChunkingPolicy.CHAPTER).success());
            assertEquals(2, manager.getSemanticTree().chunks().size());
            assertEquals(cn.nexon.zerovector.core.model.NodeType.DOCUMENT,
                    manager.getSemanticTree().rootNode().type());
            assertEquals(2, manager.getSemanticTree().rootNode().childrenIds().size());

            NavigationResult result = manager.navigate("alpha");
            assertEquals(1, result.documents().size());
            assertNull(result.documents().get(0).content());
            String section = manager.readChunkContent(result.documents().get(0));
            assertTrue(section.contains("Alpha"));
            assertTrue(section.contains("alpha body"));
            assertFalse(section.contains("Beta"));
        }
    }

    @Test
    void smallMarkdownDocumentStaysWholeByDefault() throws Exception {
        String content = "# Alpha\n alpha body\n\n# Beta\n beta body\n";
        Path file = write("whole.md", content);
        try (SemanticTreeManager manager = manager(tempDir.resolve("kb-whole"))) {
            assertTrue(manager.addDocument(file).success());
            assertEquals(1, manager.getSemanticTree().chunks().size());
            assertEquals(cn.nexon.zerovector.core.model.NodeType.LEAF,
                    manager.getSemanticTree().rootNode().type());

            NavigationResult result = manager.navigate("alpha");
            assertEquals(1, result.documents().size());
            String whole = manager.readChunkContent(result.documents().get(0));
            assertTrue(whole.contains("Alpha"));
            assertTrue(whole.contains("Beta"));
        }
    }

    @Test
    void referenceMode_readsOriginalFileAndFailsAfterDeletion() throws Exception {
        Path file = write("reference.md", "# Ref\n alpha reference body\n");
        try (SemanticTreeManager manager = manager(tempDir.resolve("kb-reference"))) {
            assertTrue(manager.addDocument(file, cn.nexon.zerovector.core.model.SourceMode.REFERENCE).success());
            NavigationResult result = manager.navigate("alpha");
            assertEquals(1, result.documents().size());
            assertTrue(manager.readChunkContent(result.documents().get(0)).contains("reference body"));

            Files.delete(file);
            assertThrows(cn.nexon.zerovector.core.exception.StorageException.class,
                    () -> manager.readChunkContent(result.documents().get(0)));
        }
    }

    @Test
    void referenceModeStreamsLargeFileAndReadsOnlyMatchedSection() throws Exception {
        String content = "# Alpha\n" + "alpha body line\n".repeat(200)
                + "# Beta\n" + "beta body line\n".repeat(200);
        Path file = write("reference-large.md", content);

        try (SemanticTreeManager manager = manager(tempDir.resolve("kb-reference-large"), 4000, 500)) {
            assertTrue(manager.addDocument(file,
                    cn.nexon.zerovector.core.model.SourceMode.REFERENCE).success());
            assertTrue(manager.getSemanticTree().chunks().size() > 2);

            NavigationResult result = manager.navigate("alpha");
            assertEquals(1, result.documents().size());
            String section = manager.readChunkContent(result.documents().get(0));
            assertTrue(section.contains("alpha body"), section);
            assertFalse(section.contains("Beta"));
            assertFalse(section.contains("beta body"));
        }
    }

    @Test
    void charsetDetectionFallbackHandlesGbkAfterLargeAsciiPrefix() throws Exception {
        String content = "alpha line\n".repeat(6000) + "中文内容\n";
        Path file = tempDir.resolve("gbk-after-ascii.md");
        Files.write(file, content.getBytes(java.nio.charset.Charset.forName("GBK")));

        try (SemanticTreeManager manager = manager(tempDir.resolve("kb-gbk-fallback"))) {
            DocumentUploadResult result = manager.addDocument(file);
            assertTrue(result.success(), result.message());

            NavigationResult navigation = manager.navigate("alpha");
            assertEquals(1, navigation.documents().size());
            assertEquals(content, manager.readChunkContent(navigation.documents().get(0)));
        }
    }

    @Test
    void duplicateDocument_isSkipped() throws Exception {
        Path file = write("duplicate.md", "same content");
        try (SemanticTreeManager manager = manager(tempDir.resolve("kb2"))) {
            assertTrue(manager.addDocument(file).success());
            DocumentUploadResult second = manager.addDocument(file);
            assertTrue(second.skipped());
            assertEquals(1, manager.getSemanticTree().chunks().size());
        }
    }

    @Test
    void largeDocument_isSplitAndSummarized() throws Exception {
        String content = "alpha section one. ".repeat(50) + "\n" + "beta section two. ".repeat(50);
        Path file = write("large.md", content);
        try (SemanticTreeManager manager = manager(tempDir.resolve("kb3"), 80)) {
            assertTrue(manager.addDocument(file,
                    cn.nexon.zerovector.core.model.SourceMode.COPY,
                    cn.nexon.zerovector.core.model.ChunkingPolicy.CHAPTER).success());
            assertTrue(manager.getSemanticTree().chunks().size() > 1);
            NavigationResult result = manager.navigate("alpha");
            assertEquals(1, result.documents().size());
            assertTrue(content(manager, result).contains("alpha section"));
        }
    }

    private String content(SemanticTreeManager manager, NavigationResult result) {
        assertFalse(result.documents().isEmpty());
        return manager.readChunkContent(result.documents().get(0));
    }

    private SemanticTreeManager manager(Path path) {
        return manager(path, 4000);
    }

    @Test
    void autoPolicySplitsOnlyAboveThreshold() throws Exception {
        Path small = write("auto-small.md", "# A\n a\n# B\n b\n");
        Path large = write("auto-large.md", "alpha line\n".repeat(100));
        try (SemanticTreeManager manager = manager(tempDir.resolve("kb-auto"), 4000, 500)) {
            assertTrue(manager.addDocument(small).success());
            assertEquals(1, manager.getSemanticTree().chunks().size());

            assertTrue(manager.addDocument(large).success());
            assertTrue(manager.getSemanticTree().chunks().size() > 1);
        }
    }

    private SemanticTreeManager manager(Path path, int chunkChars, long threshold) {
        DocumentComprehender comprehender = new DocumentComprehender(new ScriptedLLM(), chunkChars);
        return new SemanticTreeManager(new ScriptedLLM(), comprehender, path,
                10, threshold, new ConcurrencyProperties(), null);
    }

    private SemanticTreeManager manager(Path path, int chunkChars) {
        DocumentComprehender comprehender = new DocumentComprehender(new ScriptedLLM(), chunkChars);
        return new SemanticTreeManager(new ScriptedLLM(), comprehender, path,
                10, new ConcurrencyProperties(), null);
    }

    private Path write(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * 不依赖真实模型的脚本化 LLM：按请求类型返回固定结构。
     */
    private static final class ScriptedLLM implements LLMProvider {

        private static final Pattern INDEX = Pattern.compile("\\[(\\d+)]");

        @Override
        public LLMResponse chat(String prompt, SmartCacheStrategy.RequestType type) {
            return switch (type) {
                case COMPREHEND_CHUNK -> LLMResponse.success(comprehension(prompt), 1L);
                case GENERATE_SUMMARY -> LLMResponse.success("summary", 1L);
                case CLUSTER_DOCUMENTS -> LLMResponse.success(clusters(prompt), 1L);
                case EXTRACT_KEYWORDS -> LLMResponse.success(keywords(prompt), 1L);
                case EXTRACT_ENTITIES -> LLMResponse.success("[\"Entity\"]", 1L);
                case GENERATE_EXAMPLE_QUESTIONS -> LLMResponse.success("[\"example question\"]", 1L);
                case EXTRACT_QUERY_KEYWORDS -> LLMResponse.success(
                        prompt.contains("alpha") ? "alpha" : prompt.contains("gamma") ? "gamma" : "beta", 1L);
                case DECIDE_NAVIGATION -> LLMResponse.success(
                        "{\"action\":\"select_child\",\"selectedIndex\":0,\"reasoning\":\"first\",\"confidence\":0.9}",
                        1L);
                case ANSWER_QUESTION -> LLMResponse.success("answer", 1L);
            };
        }

        private static String comprehension(String prompt) {
            String keyword = prompt.contains("alpha") ? "alpha" : prompt.contains("gamma") ? "gamma" : "beta";
            return """
                    {"summary":"%s summary","keywordDefinitions":[{"keyword":"%s","definition":"d","context":"c"}],
                     "entities":["Entity"],"exampleQuestions":["example question"]}
                    """.formatted(keyword, keyword);
        }

        private static String keywords(String prompt) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (String keyword : List.of("alpha", "beta", "gamma")) {
                if (prompt.contains(keyword)) {
                    if (!first) {
                        json.append(',');
                    }
                    json.append('"').append(keyword).append('"');
                    first = false;
                }
            }
            return json.append(']').toString();
        }

        private static String clusters(String prompt) {
            Matcher matcher = INDEX.matcher(prompt);
            StringBuilder json = new StringBuilder("{\"clusters\":[");
            boolean first = true;
            while (matcher.find()) {
                if (!first) {
                    json.append(',');
                }
                json.append("{\"name\":\"category").append(matcher.group(1))
                        .append("\",\"chunkIndices\":[").append(matcher.group(1)).append("]}");
                first = false;
            }
            return json.append("]}").toString();
        }
    }
}
