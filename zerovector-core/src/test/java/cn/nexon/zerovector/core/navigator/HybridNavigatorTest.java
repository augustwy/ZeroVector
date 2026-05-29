package cn.nexon.zerovector.core.navigator;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.core.exception.PromptLoadException;
import cn.nexon.zerovector.core.hook.DefaultHookExecutor;
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.model.*;
import cn.nexon.zerovector.core.storage.spi.ChunkStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HybridNavigatorTest {

    @Mock LLMProvider llm;
    @Mock ChunkStorage chunkStore;

    private SemanticTree tree;
    private KeywordDictionary dictionary;
    private HybridNavigator navigator;

    private static final String ROOT_ID = "root";
    private static final String TECH_CAT_ID = "tech_cat";
    private static final String TECH_LEAF_ID = "tech_leaf";
    private static final String LEGAL_LEAF_ID = "legal_leaf";
    private static final String CHUNK1_ID = "chunk1";
    private static final String CHUNK2_ID = "chunk2";

    @BeforeEach
    void setUp() {
        TreeNode root = new TreeNode(ROOT_ID, "Root", "Root category", NodeType.CATEGORY,
            List.of(TECH_CAT_ID, LEGAL_LEAF_ID), List.of(),
            List.of("tech", "legal"), List.of("knowledge"), List.of("What is this about?"));
        TreeNode techCat = new TreeNode(TECH_CAT_ID, "Technology", "Technical documents", NodeType.CATEGORY,
            List.of(TECH_LEAF_ID), List.of(),
            List.of("java", "programming"), List.of("code"), List.of("How to code?"));
        TreeNode techLeaf = new TreeNode(TECH_LEAF_ID, "Java Guide", "Java programming guide", NodeType.LEAF,
            List.of(), List.of(CHUNK1_ID),
            List.of("java", "spring"), List.of("tutorial"), List.of("What is Java?"));
        TreeNode legalLeaf = new TreeNode(LEGAL_LEAF_ID, "Legal", "Legal documents", NodeType.LEAF,
            List.of(), List.of(CHUNK2_ID),
            List.of("law", "regulation"), List.of("compliance"), List.of("What is the law?"));

        tree = new SemanticTree(root, Map.of(
            ROOT_ID, root,
            TECH_CAT_ID, techCat,
            TECH_LEAF_ID, techLeaf,
            LEGAL_LEAF_ID, legalLeaf
        ), Map.of(
            CHUNK1_ID, DocumentChunk.withContent(CHUNK1_ID, "Java content here", "Java summary", Map.of()),
            CHUNK2_ID, DocumentChunk.withContent(CHUNK2_ID, "Legal content here", "Legal summary", Map.of())
        ));

        dictionary = new KeywordDictionary();
        dictionary.addEntry("java", TECH_LEAF_ID, 1.0);
        dictionary.addEntry("programming", TECH_CAT_ID, 1.0);
        dictionary.addEntry("law", LEGAL_LEAF_ID, 1.0);
    }

    @Test
    void navigate_keywordDirectMatch_returnsLeafContent() {
        // "java" matches tech_leaf directly (a LEAF node) — no navigation step needed
        when(llm.chat(anyString(), eq(SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS)))
            .thenReturn(LLMResponse.success("java", 5L));

        navigator = new HybridNavigator(tree, dictionary, llm, chunkStore, 10, new DefaultHookExecutor());
        NavigationResult result = navigator.navigate("How to use Java?");

        assertNotNull(result);
        assertFalse(result.documents().isEmpty());
        assertEquals("Java content here", result.documents().get(0).content());
    }

    @Test
    void navigate_keywordMatchesCategory_thenNavigatesToLeaf() {
        // "programming" matches tech_cat (a CATEGORY node) — needs navigation
        when(llm.chat(anyString(), eq(SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS)))
            .thenReturn(LLMResponse.success("programming", 5L));

        // Navigation decision from tech_cat -> tech_leaf (index 0)
        when(llm.chat(anyString(), eq(SmartCacheStrategy.RequestType.DECIDE_NAVIGATION)))
            .thenReturn(LLMResponse.success(
                "{\"selectedIndex\": 0, \"reasoning\": \"relevant\", \"confidence\": 0.95}", 10L));

        navigator = new HybridNavigator(tree, dictionary, llm, chunkStore, 10, new DefaultHookExecutor());
        NavigationResult result = navigator.navigate("Programming guide");

        assertNotNull(result);
        assertFalse(result.documents().isEmpty());
        assertEquals("Java content here", result.documents().get(0).content());
    }

    @Test
    void navigate_noKeywordMatch_startsFromRoot() {
        // No keyword match — starts from root, needs 2 navigation steps (root -> tech_cat -> leaf)
        when(llm.chat(anyString(), eq(SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS)))
            .thenReturn(LLMResponse.success("unknown_term", 5L));

        when(llm.chat(anyString(), eq(SmartCacheStrategy.RequestType.DECIDE_NAVIGATION)))
            .thenReturn(LLMResponse.success(
                "{\"selectedIndex\": 0, \"reasoning\": \"tech\", \"confidence\": 0.7}", 10L))
            .thenReturn(LLMResponse.success(
                "{\"selectedIndex\": 0, \"reasoning\": \"java guide\", \"confidence\": 0.9}", 10L));

        navigator = new HybridNavigator(tree, dictionary, llm, chunkStore, 10, new DefaultHookExecutor());
        NavigationResult result = navigator.navigate("Something about technology");

        assertNotNull(result);
        assertFalse(result.documents().isEmpty());
    }

    @Test
    void navigate_llmFailure_duringExtraction_promptsFallback() {
        // LLM throws PromptLoadException during keyword extraction — falls back to raw query match
        when(llm.chat(anyString(), eq(SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS)))
            .thenThrow(new PromptLoadException("extract", "PROMPT_005", "LLM error"));

        // Raw query "java programming" splits to ["java", "programming"]
        // "java" -> tech_leaf (LEAF), so no navigation step
        navigator = new HybridNavigator(tree, dictionary, llm, chunkStore, 10, new DefaultHookExecutor());
        NavigationResult result = navigator.navigate("java programming");

        assertNotNull(result);
    }

    @Test
    void navigate_lowConfidence_triggersFallback() {
        // LLM extracts keywords that match a CATEGORY node
        when(llm.chat(anyString(), eq(SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS)))
            .thenReturn(LLMResponse.success("programming", 5L));

        // But navigation confidence is too low (< 0.3), triggers fallback
        when(llm.chat(anyString(), eq(SmartCacheStrategy.RequestType.DECIDE_NAVIGATION)))
            .thenReturn(LLMResponse.success(
                "{\"selectedIndex\": 0, \"reasoning\": \"unsure\", \"confidence\": 0.2}", 10L));

        navigator = new HybridNavigator(tree, dictionary, llm, chunkStore, 10, new DefaultHookExecutor());
        NavigationResult result = navigator.navigate("Programming stuff");

        assertNotNull(result);
    }

    @Test
    void navigate_llmParseFailure_throwsNavigationException() {
        when(llm.chat(anyString(), eq(SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS)))
            .thenReturn(LLMResponse.success("anything", 5L));

        when(llm.chat(anyString(), eq(SmartCacheStrategy.RequestType.DECIDE_NAVIGATION)))
            .thenReturn(LLMResponse.success("not valid json at all", 10L));

        navigator = new HybridNavigator(tree, dictionary, llm, chunkStore, 10, new DefaultHookExecutor());
        assertThrows(Exception.class,
            () -> navigator.navigate("Test query"));
    }

    @Test
    void navigate_fallbackAfterNoKeywordMatch_usesKeywords() {
        // LLM extracts "law" from query
        when(llm.chat(anyString(), eq(SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS)))
            .thenReturn(LLMResponse.success("law", 5L));

        // "law" matches legal_leaf (LEAF) directly — no navigation step
        navigator = new HybridNavigator(tree, dictionary, llm, chunkStore, 10, new DefaultHookExecutor());
        NavigationResult result = navigator.navigate("What is the law?");

        assertNotNull(result);
        assertFalse(result.documents().isEmpty());
        assertEquals("Legal content here", result.documents().get(0).content());
    }

    @Test
    void navigate_multipleKeywords_aggregatesScores() {
        when(llm.chat(anyString(), eq(SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS)))
            .thenReturn(LLMResponse.success("java\ntutorial", 5L));

        navigator = new HybridNavigator(tree, dictionary, llm, chunkStore, 10, new DefaultHookExecutor());
        NavigationResult result = navigator.navigate("Java tutorial");

        assertNotNull(result);
        boolean hasJavaContent = result.documents().stream()
            .anyMatch(d -> "Java content here".equals(d.content()));
        assertTrue(hasJavaContent, "Should find Java content from keyword match");
    }

    @Test
    void navigate_navigationPath_containsSteps() {
        when(llm.chat(anyString(), eq(SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS)))
            .thenReturn(LLMResponse.success("programming", 5L));

        when(llm.chat(anyString(), eq(SmartCacheStrategy.RequestType.DECIDE_NAVIGATION)))
            .thenReturn(LLMResponse.success(
                "{\"selectedIndex\": 0, \"reasoning\": \"relevant\", \"confidence\": 0.95}", 10L));

        navigator = new HybridNavigator(tree, dictionary, llm, chunkStore, 10, new DefaultHookExecutor());
        NavigationResult result = navigator.navigate("Programming guide");

        assertNotNull(result.path());
        assertFalse(result.path().isEmpty());
    }
}
