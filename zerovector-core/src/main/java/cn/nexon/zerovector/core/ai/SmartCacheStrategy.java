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

import cn.nexon.zerovector.core.util.MD5Util;

import java.util.Locale;

public final class SmartCacheStrategy {

    private SmartCacheStrategy() {
    }

    public enum RequestType {
        COMPREHEND_CHUNK,
        GENERATE_SUMMARY,
        CLUSTER_DOCUMENTS,
        EXTRACT_KEYWORDS,
        EXTRACT_ENTITIES,
        GENERATE_EXAMPLE_QUESTIONS,
        DECIDE_NAVIGATION,
        EXTRACT_QUERY_KEYWORDS,
        /** 最终问答：结果与完整上下文强相关，不缓存。 */
        ANSWER_QUESTION
    }

    public static String generateCacheKey(RequestType type, String prompt) {
        String hash = prompt == null ? "null" : MD5Util.calculateMD5(prompt);
        return type.name().toLowerCase(Locale.ROOT) + ":" + hash;
    }

    public static boolean isSimilarPrompt(String prompt1, String prompt2, SimilarityConfig config) {
        if (!config.enableSimilarityMatching() || prompt1 == null || prompt2 == null) {
            return false;
        }

        String normalized1 = normalizePrompt(prompt1);
        String normalized2 = normalizePrompt(prompt2);
        if (normalized1.equals(normalized2)) {
            return true;
        }
        if (normalized1.length() > config.maxSimilarityLength()
                || normalized2.length() > config.maxSimilarityLength()) {
            return false;
        }
        return calculateSimilarity(normalized1, normalized2) >= config.similarityThreshold();
    }

    private static String normalizePrompt(String prompt) {
        return prompt.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static double calculateSimilarity(String s1, String s2) {
        if (s1.isEmpty() && s2.isEmpty()) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;
        int maxLength = Math.max(s1.length(), s2.length());
        int distance = levenshteinDistance(s1, s2);
        return 1.0 - (double) distance / maxLength;
    }

    private static int levenshteinDistance(String s1, String s2) {
        int[] prev = new int[s2.length() + 1];
        int[] curr = new int[s2.length() + 1];
        for (int j = 0; j <= s2.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= s1.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }
        return prev[s2.length()];
    }

    public static CacheConfig getConfigForType(RequestType type) {
        return switch (type) {
            case COMPREHEND_CHUNK -> CacheConfig.DEFAULT_COMPREHEND;
            case GENERATE_SUMMARY -> CacheConfig.DEFAULT_SUMMARY;
            case CLUSTER_DOCUMENTS -> CacheConfig.DEFAULT_CLUSTER;
            case EXTRACT_KEYWORDS, EXTRACT_QUERY_KEYWORDS -> CacheConfig.DEFAULT_KEYWORDS;
            case EXTRACT_ENTITIES -> CacheConfig.DEFAULT_ENTITIES;
            case GENERATE_EXAMPLE_QUESTIONS -> CacheConfig.DEFAULT_QUESTIONS;
            case DECIDE_NAVIGATION -> CacheConfig.DEFAULT_NAVIGATION;
            case ANSWER_QUESTION -> CacheConfig.disabled();
        };
    }
}
