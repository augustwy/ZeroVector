package cn.nexon.zerovector.core.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LLMPromptTemplatesTest {

    @Test
    void generateSummary_containsTitleAndContent() {
        String prompt = LLMPromptTemplates.generateSummary("Test Title", "Some content here");
        assertTrue(prompt.contains("Test Title"));
        assertTrue(prompt.contains("Some content here"));
        assertTrue(prompt.contains("摘要"));
    }

    @Test
    void extractKeywords_containsContent() {
        String prompt = LLMPromptTemplates.extractKeywords("keyword extraction test content");
        assertTrue(prompt.contains("keyword extraction test content"));
        assertTrue(prompt.contains("关键词"));
    }

    @Test
    void extractEntities_containsContent() {
        String prompt = LLMPromptTemplates.extractEntities("entity extraction test");
        assertTrue(prompt.contains("entity extraction test"));
        assertTrue(prompt.contains("实体"));
    }

    @Test
    void generateExampleQuestions_containsContent() {
        String prompt = LLMPromptTemplates.generateExampleQuestions("example content");
        assertTrue(prompt.contains("example content"));
        assertTrue(prompt.contains("示例问题"));
    }

    @Test
    void decideNavigation_containsQueryAndNodes() {
        String prompt = LLMPromptTemplates.decideNavigation(
            "test query", "root", "root description",
            java.util.List.of("Child1 - child1 desc", "Child2 - child2 desc"));
        assertTrue(prompt.contains("test query"));
        assertTrue(prompt.contains("root"));
        assertTrue(prompt.contains("Child1"));
        assertTrue(prompt.contains("child1 desc"));
        assertTrue(prompt.contains("selectedIndex"));
        assertTrue(prompt.contains("confidence"));
    }

    @Test
    void decideNavigation_emptyChildNodes() {
        String prompt = LLMPromptTemplates.decideNavigation(
            "query", "node", "desc", java.util.List.of());
        assertTrue(prompt.contains("query"));
        assertTrue(prompt.contains("node"));
        assertTrue(prompt.contains("selectedIndex"));
    }

    @Test
    void comprehendChunk_containsChunksAndContext() {
        String prompt = LLMPromptTemplates.comprehendChunk("some context here", 1, 3);
        assertTrue(prompt.contains("第1部分"));
        assertTrue(prompt.contains("共3部分"));
        assertTrue(prompt.contains("some context here"));
        assertTrue(prompt.contains("keywordDefinitions"));
        assertTrue(prompt.contains("exampleQuestions"));
    }

    @Test
    void extractQueryKeywords_containsQuery() {
        String prompt = LLMPromptTemplates.extractQueryKeywords("user search query");
        assertTrue(prompt.contains("user search query"));
        assertTrue(prompt.contains("关键词"));
    }

    @Test
    void clusterDocumentChunks_containsChunkContent() {
        String prompt = LLMPromptTemplates.clusterDocumentChunks(
            java.util.List.of("chunk zero", "chunk one", "chunk two"));
        assertTrue(prompt.contains("[0]"));
        assertTrue(prompt.contains("[1]"));
        assertTrue(prompt.contains("[2]"));
        assertTrue(prompt.contains("chunk zero"));
        assertTrue(prompt.contains("chunk one"));
        assertTrue(prompt.contains("chunk two"));
        assertTrue(prompt.contains("clusters"));
    }

    @Test
    void clusterDocumentChunks_truncatesLongContent() {
        String longContent = "a".repeat(500);
        String prompt = LLMPromptTemplates.clusterDocumentChunks(
            java.util.List.of(longContent));
        // Should be truncated to 200 chars
        assertTrue(prompt.contains("a".repeat(200)));
    }
}
