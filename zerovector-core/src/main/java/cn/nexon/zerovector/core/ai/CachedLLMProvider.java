package cn.nexon.zerovector.core.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class CachedLLMProvider implements LLMProvider {
    private final LLMProvider delegate;
    private final Cache<String, List<String>> listCache;
    private final Cache<String, String> stringCache;
    
    public CachedLLMProvider(LLMProvider delegate) {
        this.delegate = delegate;
        this.listCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();
        this.stringCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();
    }
    
    @Override
    public String comprehendChunk(String prompt) {
        return delegate.comprehendChunk(prompt);
    }
    
    @Override
    public String generateSummary(String prompt) {
        return stringCache.get("summary:" + prompt.hashCode(), key -> delegate.generateSummary(prompt));
    }

    @Override
    public String clusterDocuments(String prompt) {
        return delegate.clusterDocuments(prompt);
    }
    
    @Override
    public String extractKeywords(String prompt) {
        return delegate.extractKeywords(prompt);
    }
    
    @Override
    public String extractEntities(String prompt) {
        return delegate.extractEntities(prompt);
    }
    
    @Override
    public String generateExampleQuestions(String prompt) {
        return delegate.generateExampleQuestions(prompt);
    }
    
    @Override
    public String decideNavigation(String prompt) {
        return delegate.decideNavigation(prompt);
    }
    
    public void clearCache() {
        listCache.invalidateAll();
        stringCache.invalidateAll();
    }
}
