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

package cn.nexon.zerovector.core.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SmartCacheStrategyTest {

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
    void isSimilarPrompt_exactMatch_returnsTrue() {
        assertTrue(SmartCacheStrategy.isSimilarPrompt(
            "What is ZeroVector", "What is ZeroVector", SimilarityConfig.DEFAULT));
    }

    @Test
    void isSimilarPrompt_oneCharDiff_returnsTrue() {
        // "hello xorld" vs "hello world": 1 char diff, maxLen=11, sim=0.909 > 0.85
        assertTrue(SmartCacheStrategy.isSimilarPrompt(
            "hello world",
            "hello xorld", SimilarityConfig.DEFAULT));
    }

    @Test
    void isSimilarPrompt_differentContent_returnsFalse() {
        assertFalse(SmartCacheStrategy.isSimilarPrompt(
            "How to deploy the system",
            "What is the weather today", SimilarityConfig.DEFAULT));
    }

    @Test
    void isSimilarPrompt_manyDifferences_returnsFalse() {
        assertFalse(SmartCacheStrategy.isSimilarPrompt(
            "aaaaa", "bbbbb", SimilarityConfig.DEFAULT));
    }

    @Test
    void isSimilarPrompt_nullInput_returnsFalse() {
        assertFalse(SmartCacheStrategy.isSimilarPrompt(null, "hello", SimilarityConfig.DEFAULT));
        assertFalse(SmartCacheStrategy.isSimilarPrompt("hello", null, SimilarityConfig.DEFAULT));
        assertFalse(SmartCacheStrategy.isSimilarPrompt(null, null, SimilarityConfig.DEFAULT));
    }

    @Test
    void isSimilarPrompt_disabledMatching_returnsFalse() {
        SimilarityConfig disabled = new SimilarityConfig(0.85, 500, false);
        assertFalse(SmartCacheStrategy.isSimilarPrompt("hello", "hello", disabled));
    }

    @Test
    void isSimilarPrompt_exceedsMaxLength_returnsFalse() {
        SimilarityConfig shortLimit = new SimilarityConfig(0.85, 10, true);
        assertFalse(SmartCacheStrategy.isSimilarPrompt(
            "this is a very long prompt", "this is also long but not same", shortLimit));
    }

    @Test
    void isSimilarPrompt_caseInsensitive_returnsTrue() {
        assertTrue(SmartCacheStrategy.isSimilarPrompt(
            "ZeroVector Knowledge Base",
            "zerovector knowledge base", SimilarityConfig.DEFAULT));
    }

    @Test
    void isSimilarPrompt_extraWhitespaceNormalized_returnsTrue() {
        assertTrue(SmartCacheStrategy.isSimilarPrompt(
            "hello   world  foo",
            "hello world foo", SimilarityConfig.DEFAULT));
    }

    @Test
    void isSimilarPrompt_emptyStrings_returnsTrue() {
        assertTrue(SmartCacheStrategy.isSimilarPrompt("", "", SimilarityConfig.DEFAULT));
    }

    @Test
    void isSimilarPrompt_oneEmptyString_returnsFalse() {
        assertFalse(SmartCacheStrategy.isSimilarPrompt("hello", "", SimilarityConfig.DEFAULT));
        assertFalse(SmartCacheStrategy.isSimilarPrompt("", "world", SimilarityConfig.DEFAULT));
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
    void similarityConfig_clampsThresholdToValidRange() {
        assertEquals(0.85, new SimilarityConfig(1.5, 500, true).similarityThreshold(), 0.001);
        assertEquals(0.85, new SimilarityConfig(-0.5, 500, true).similarityThreshold(), 0.001);
        assertEquals(0.75, new SimilarityConfig(0.75, 500, true).similarityThreshold(), 0.001);
        assertEquals(0.85, new SimilarityConfig(Double.NaN, 500, true).similarityThreshold(), 0.001);
    }

    @Test
    void similarityConfig_onlyAcceptsPositiveLength() {
        assertEquals(500, new SimilarityConfig(0.85, -5, true).maxSimilarityLength());
        assertEquals(500, new SimilarityConfig(0.85, 0, true).maxSimilarityLength());
        assertEquals(200, new SimilarityConfig(0.85, 200, true).maxSimilarityLength());
    }

    @Test
    void isSimilarPrompt_adjustedThreshold_affectsResult() {
        // "hello world" vs "hello wrld" — 2 char diffs at length 11 ≈ 0.82
        SimilarityConfig strict = new SimilarityConfig(0.99, 500, true);
        assertFalse(SmartCacheStrategy.isSimilarPrompt("hello world", "hello wrld", strict));

        SimilarityConfig loose = new SimilarityConfig(0.5, 500, true);
        assertTrue(SmartCacheStrategy.isSimilarPrompt("hello world", "hello wrld", loose));
    }
}
