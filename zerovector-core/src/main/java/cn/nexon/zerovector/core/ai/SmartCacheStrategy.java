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

public class SmartCacheStrategy {
    
    private static volatile double SIMILARITY_THRESHOLD = 0.85;
    private static volatile int MAX_SIMILARITY_LENGTH = 500;
    private static volatile boolean ENABLE_SIMILARITY_MATCHING = true;
    
    /**
     * 设置相似性阈值
     * @param threshold 相似性阈值，范围0-1
     */
    public static void setSimilarityThreshold(double threshold) {
        if (threshold >= 0 && threshold <= 1) {
            SIMILARITY_THRESHOLD = threshold;
        }
    }
    
    /**
     * 设置最大相似性匹配长度
     * @param length 最大长度
     */
    public static void setMaxSimilarityLength(int length) {
        if (length > 0) {
            MAX_SIMILARITY_LENGTH = length;
        }
    }
    
    /**
     * 设置是否启用相似性匹配
     * @param enabled 是否启用
     */
    public static void setEnableSimilarityMatching(boolean enabled) {
        ENABLE_SIMILARITY_MATCHING = enabled;
    }
    
    /**
     * 获取相似性阈值
     * @return 相似性阈值
     */
    public static double getSimilarityThreshold() {
        return SIMILARITY_THRESHOLD;
    }
    
    /**
     * 获取最大相似性匹配长度
     * @return 最大长度
     */
    public static int getMaxSimilarityLength() {
        return MAX_SIMILARITY_LENGTH;
    }
    
    /**
     * 获取是否启用相似性匹配
     * @return 是否启用
     */
    public static boolean isEnableSimilarityMatching() {
        return ENABLE_SIMILARITY_MATCHING;
    }
    
    public enum RequestType {
        COMPREHEND_CHUNK,
        GENERATE_SUMMARY,
        CLUSTER_DOCUMENTS,
        EXTRACT_KEYWORDS,
        EXTRACT_ENTITIES,
        GENERATE_EXAMPLE_QUESTIONS,
        DECIDE_NAVIGATION,
        EXTRACT_QUERY_KEYWORDS
    }
    
    public static String generateCacheKey(RequestType type, String prompt) {
        return type.name().toLowerCase() + ":" + hashPrompt(prompt);
    }
    
    public static String generateCacheKey(String prefix, String prompt) {
        return prefix + ":" + hashPrompt(prompt);
    }
    
    private static String hashPrompt(String prompt) {
        if (prompt == null) {
            return "null";
        }
        return MD5Util.calculateMD5(prompt);
    }
    
    public static boolean isSimilarPrompt(String prompt1, String prompt2) {
        if (!ENABLE_SIMILARITY_MATCHING) {
            return false;
        }
        
        if (prompt1 == null || prompt2 == null) {
            return false;
        }
        
        String normalized1 = normalizePrompt(prompt1);
        String normalized2 = normalizePrompt(prompt2);
        
        if (normalized1.equals(normalized2)) {
            return true;
        }
        
        if (normalized1.length() > MAX_SIMILARITY_LENGTH || normalized2.length() > MAX_SIMILARITY_LENGTH) {
            return false;
        }
        
        return calculateSimilarity(normalized1, normalized2) >= SIMILARITY_THRESHOLD;
    }
    
    private static String normalizePrompt(String prompt) {
        return prompt.toLowerCase()
            .replaceAll("\\s+", " ")
            .trim();
    }
    
    private static double calculateSimilarity(String s1, String s2) {
        if (s1.isEmpty() && s2.isEmpty()) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;
        
        // 对长文本进行摘要处理，提高计算效率
        if (s1.length() > MAX_SIMILARITY_LENGTH || s2.length() > MAX_SIMILARITY_LENGTH) {
            s1 = summarizeText(s1, MAX_SIMILARITY_LENGTH);
            s2 = summarizeText(s2, MAX_SIMILARITY_LENGTH);
        }
        
        int maxLength = Math.max(s1.length(), s2.length());
        int distance = levenshteinDistance(s1, s2);
        
        return 1.0 - (double) distance / maxLength;
    }
    
    private static int levenshteinDistance(String s1, String s2) {
        // 优化：使用一维数组减少空间复杂度
        int[] prev = new int[s2.length() + 1];
        int[] curr = new int[s2.length() + 1];
        
        for (int j = 0; j <= s2.length(); j++) {
            prev[j] = j;
        }
        
        for (int i = 1; i <= s1.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                    Math.min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                );
            }
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }
        
        return prev[s2.length()];
    }
    
    private static String summarizeText(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        
        // 简单摘要算法：取文本开头和结尾的部分
        int half = maxLength / 2;
        return text.substring(0, half) + "..." + text.substring(text.length() - half);
    }
    
    public static CacheConfig getConfigForType(RequestType type) {
        return switch (type) {
            case COMPREHEND_CHUNK -> CacheConfig.DEFAULT_COMPREHEND;
            case GENERATE_SUMMARY -> CacheConfig.DEFAULT_SUMMARY;
            case CLUSTER_DOCUMENTS -> CacheConfig.DEFAULT_CLUSTER;
            case EXTRACT_KEYWORDS -> CacheConfig.DEFAULT_KEYWORDS;
            case EXTRACT_ENTITIES -> CacheConfig.DEFAULT_ENTITIES;
            case GENERATE_EXAMPLE_QUESTIONS -> CacheConfig.DEFAULT_QUESTIONS;
            case DECIDE_NAVIGATION -> CacheConfig.DEFAULT_NAVIGATION;
            case EXTRACT_QUERY_KEYWORDS -> CacheConfig.DEFAULT_KEYWORDS;
        };
    }
}
