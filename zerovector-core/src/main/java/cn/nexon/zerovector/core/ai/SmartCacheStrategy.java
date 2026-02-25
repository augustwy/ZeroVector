package cn.nexon.zerovector.core.ai;

import java.util.Objects;

public class SmartCacheStrategy {
    
    private static final double SIMILARITY_THRESHOLD = 0.85;
    private static final int MAX_SIMILARITY_LENGTH = 500;
    private static final boolean ENABLE_SIMILARITY_MATCHING = true;
    
    public enum RequestType {
        COMPREHEND_CHUNK,
        GENERATE_SUMMARY,
        CLUSTER_DOCUMENTS,
        EXTRACT_KEYWORDS,
        EXTRACT_ENTITIES,
        GENERATE_EXAMPLE_QUESTIONS,
        DECIDE_NAVIGATION
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
        return String.valueOf(Objects.hash(prompt));
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
        
        int maxLength = Math.max(s1.length(), s2.length());
        int distance = levenshteinDistance(s1, s2);
        
        return 1.0 - (double) distance / maxLength;
    }
    
    private static int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        
        for (int i = 0; i <= s1.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= s2.length(); j++) {
            dp[0][j] = j;
        }
        
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                );
            }
        }
        
        return dp[s1.length()][s2.length()];
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
        };
    }
}
