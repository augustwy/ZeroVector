package cn.nexon.zerovector.core.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SmartCacheStrategyTest {

    @AfterEach
    void resetThresholds() {
        SmartCacheStrategy.setSimilarityThreshold(0.85);
        SmartCacheStrategy.setMaxSimilarityLength(500);
        SmartCacheStrategy.setEnableSimilarityMatching(true);
    }

    @Test
    void generateCacheKey_differsByType() {
        String key1 = SmartCacheStrategy.generateCacheKey(
            SmartCacheStrategy.RequestType.COMPREHEND_CHUNK, "hello");
        String key2 = SmartCacheStrategy.generateCacheKey(
            SmartCacheStrategy.RequestType.GENERATE_SUMMARY, "hello");
        assertNotEquals(key1, key2);
        assertTrue(key1.startsWith("comprehend_chunk:"));
        assertTrue(key2.startsWith("generate_summary:"));
    }

    @Test
    void generateCacheKey_sameInput_returnsSameKey() {
        String key1 = SmartCacheStrategy.generateCacheKey(
            SmartCacheStrategy.RequestType.DECIDE_NAVIGATION, "some query");
        String key2 = SmartCacheStrategy.generateCacheKey(
            SmartCacheStrategy.RequestType.DECIDE_NAVIGATION, "some query");
        assertEquals(key1, key2);
    }

    @Test
    void generateCacheKey_differsByInput() {
        String key1 = SmartCacheStrategy.generateCacheKey(
            SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS, "query A");
        String key2 = SmartCacheStrategy.generateCacheKey(
            SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS, "query B");
        assertNotEquals(key1, key2);
    }

    @Test
    void generateCacheKey_withCustomPrefix() {
        String key = SmartCacheStrategy.generateCacheKey("custom_prefix", "hello");
        assertTrue(key.startsWith("custom_prefix:"));
    }

    @Test
    void isSimilarPrompt_exactMatch_returnsTrue() {
        assertTrue(SmartCacheStrategy.isSimilarPrompt(
            "What is ZeroVector", "What is ZeroVector"));
    }

    @Test
    void isSimilarPrompt_oneCharDiff_returnsTrue() {
        // "hello xorld" vs "hello world": 1 char diff, maxLen=11, sim=0.909 > 0.85
        assertTrue(SmartCacheStrategy.isSimilarPrompt(
            "hello world",
            "hello xorld"));
    }

    @Test
    void isSimilarPrompt_differentContent_returnsFalse() {
        assertFalse(SmartCacheStrategy.isSimilarPrompt(
            "How to deploy the system",
            "What is the weather today"));
    }

    @Test
    void isSimilarPrompt_singleCharDiff_returnsTrue() {
        // "hello world" vs "hello xorld" — 1 char diffs at length 11 = 0.91 > 0.85
        assertTrue(SmartCacheStrategy.isSimilarPrompt(
            "hello world", "hello xorld"));
    }

    @Test
    void isSimilarPrompt_manyDifferences_returnsFalse() {
        assertFalse(SmartCacheStrategy.isSimilarPrompt(
            "aaaaa", "bbbbb"));
    }

    @Test
    void isSimilarPrompt_nullInput_returnsFalse() {
        assertFalse(SmartCacheStrategy.isSimilarPrompt(null, "hello"));
        assertFalse(SmartCacheStrategy.isSimilarPrompt("hello", null));
        assertFalse(SmartCacheStrategy.isSimilarPrompt(null, null));
    }

    @Test
    void isSimilarPrompt_disabledMatching_returnsFalse() {
        SmartCacheStrategy.setEnableSimilarityMatching(false);
        assertFalse(SmartCacheStrategy.isSimilarPrompt("hello", "hello"));
    }

    @Test
    void isSimilarPrompt_exceedsMaxLength_returnsFalse() {
        SmartCacheStrategy.setMaxSimilarityLength(10);
        assertFalse(SmartCacheStrategy.isSimilarPrompt(
            "this is a very long prompt", "this is also long but not same"));
    }

    @Test
    void isSimilarPrompt_caseInsensitive_returnsTrue() {
        assertTrue(SmartCacheStrategy.isSimilarPrompt(
            "ZeroVector Knowledge Base",
            "zerovector knowledge base"));
    }

    @Test
    void isSimilarPrompt_extraWhitespaceNormalized_returnsTrue() {
        assertTrue(SmartCacheStrategy.isSimilarPrompt(
            "hello   world  foo",
            "hello world foo"));
    }

    @Test
    void isSimilarPrompt_emptyStrings_returnsTrue() {
        assertTrue(SmartCacheStrategy.isSimilarPrompt("", ""));
    }

    @Test
    void isSimilarPrompt_oneEmptyString_returnsFalse() {
        assertFalse(SmartCacheStrategy.isSimilarPrompt("hello", ""));
        assertFalse(SmartCacheStrategy.isSimilarPrompt("", "world"));
    }

    @Test
    void getConfigForType_returnsCorrectDefaults() {
        assertEquals(CacheConfig.DEFAULT_COMPREHEND,
            SmartCacheStrategy.getConfigForType(SmartCacheStrategy.RequestType.COMPREHEND_CHUNK));
        assertEquals(CacheConfig.DEFAULT_SUMMARY,
            SmartCacheStrategy.getConfigForType(SmartCacheStrategy.RequestType.GENERATE_SUMMARY));
        assertEquals(CacheConfig.DEFAULT_CLUSTER,
            SmartCacheStrategy.getConfigForType(SmartCacheStrategy.RequestType.CLUSTER_DOCUMENTS));
        assertEquals(CacheConfig.DEFAULT_KEYWORDS,
            SmartCacheStrategy.getConfigForType(SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS));
        assertEquals(CacheConfig.DEFAULT_ENTITIES,
            SmartCacheStrategy.getConfigForType(SmartCacheStrategy.RequestType.EXTRACT_ENTITIES));
        assertEquals(CacheConfig.DEFAULT_QUESTIONS,
            SmartCacheStrategy.getConfigForType(SmartCacheStrategy.RequestType.GENERATE_EXAMPLE_QUESTIONS));
        assertEquals(CacheConfig.DEFAULT_NAVIGATION,
            SmartCacheStrategy.getConfigForType(SmartCacheStrategy.RequestType.DECIDE_NAVIGATION));
        assertEquals(CacheConfig.DEFAULT_KEYWORDS,
            SmartCacheStrategy.getConfigForType(SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS));
    }

    @Test
    void requestType_hasAllExpectedValues() {
        SmartCacheStrategy.RequestType[] types = SmartCacheStrategy.RequestType.values();
        assertEquals(8, types.length);
    }

    @Test
    void similarityThreshold_clampsToValidRange() {
        SmartCacheStrategy.setSimilarityThreshold(1.5);
        assertEquals(0.85, SmartCacheStrategy.getSimilarityThreshold(), 0.001);
        SmartCacheStrategy.setSimilarityThreshold(-0.5);
        assertEquals(0.85, SmartCacheStrategy.getSimilarityThreshold(), 0.001);
        SmartCacheStrategy.setSimilarityThreshold(0.75);
        assertEquals(0.75, SmartCacheStrategy.getSimilarityThreshold(), 0.001);
    }

    @Test
    void maxSimilarityLength_onlyAcceptsPositive() {
        SmartCacheStrategy.setMaxSimilarityLength(-5);
        assertEquals(500, SmartCacheStrategy.getMaxSimilarityLength());
        SmartCacheStrategy.setMaxSimilarityLength(0);
        assertEquals(500, SmartCacheStrategy.getMaxSimilarityLength());
        SmartCacheStrategy.setMaxSimilarityLength(200);
        assertEquals(200, SmartCacheStrategy.getMaxSimilarityLength());
    }

    @Test
    void isSimilarPrompt_adjustedThreshold_affectsResult() {
        SmartCacheStrategy.setSimilarityThreshold(0.99);
        // "hello" vs "helo" — less similar but with high threshold should fail
        assertFalse(SmartCacheStrategy.isSimilarPrompt("hello world", "hello wrld"));
        SmartCacheStrategy.setSimilarityThreshold(0.5);
        // Now the same pair should pass
        assertTrue(SmartCacheStrategy.isSimilarPrompt("hello world", "hello wrld"));
    }
}
