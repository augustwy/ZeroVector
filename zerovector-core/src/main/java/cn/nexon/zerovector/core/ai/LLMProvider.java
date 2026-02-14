package cn.nexon.zerovector.core.ai;

import java.util.List;

public interface LLMProvider {
    
    String comprehendChunk(String prompt);
    
    String generateSummary(String prompt);
    
    String clusterDocuments(String prompt);
    
    String extractKeywords(String prompt);
    
    String extractEntities(String prompt);
    
    String generateExampleQuestions(String prompt);
    
    String decideNavigation(String prompt);
}
