package cn.nexon.zerovector.springboot.service;

import cn.nexon.zerovector.core.KnowledgeBaseManager;
import cn.nexon.zerovector.core.SemanticTreeManager;
import cn.nexon.zerovector.core.ai.CacheConfig;
import cn.nexon.zerovector.core.ai.CacheStatistics;
import cn.nexon.zerovector.core.ai.CachedLLMProvider;
import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.core.model.Document;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.NavigationPath;
import cn.nexon.zerovector.core.model.NavigationResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class SemanticFacade {
    
    private final KnowledgeBaseManager knowledgeBaseManager;
    
    public SemanticFacade(KnowledgeBaseManager knowledgeBaseManager) {
        this.knowledgeBaseManager = knowledgeBaseManager;
    }
    
    public SearchResult search(String query) {
        return search(query, null);
    }
    
    public SearchResult search(String query, String knowledgeBaseName) {
        SemanticTreeManager manager = getTargetManager(knowledgeBaseName);
        NavigationResult result = manager.navigate(query);
        
        return new SearchResult(
            query,
            result.documents(),
            result.reasoning(),
            result.path()
        );
    }
    
    public void addDocument(Path filePath) {
        addDocument(filePath, null);
    }
    
    public void addDocument(Path filePath, String knowledgeBaseName) {
        SemanticTreeManager manager = getTargetManager(knowledgeBaseName);
        manager.addDocument(filePath);
    }
    
    public CompletableFuture<Void> addDocumentAsync(Path filePath) {
        return addDocumentAsync(filePath, null);
    }
    
    public CompletableFuture<Void> addDocumentAsync(Path filePath, String knowledgeBaseName) {
        SemanticTreeManager manager = getTargetManager(knowledgeBaseName);
        return manager.addDocumentAsync(filePath);
    }
    
    public void addDocuments(List<Path> filePaths) {
        addDocuments(filePaths, null);
    }
    
    public void addDocuments(List<Path> filePaths, String knowledgeBaseName) {
        SemanticTreeManager manager = getTargetManager(knowledgeBaseName);
        manager.addDocuments(filePaths);
    }
    
    public CompletableFuture<Void> addDocumentsAsync(List<Path> filePaths) {
        return addDocumentsAsync(filePaths, null);
    }
    
    public CompletableFuture<Void> addDocumentsAsync(List<Path> filePaths, String knowledgeBaseName) {
        SemanticTreeManager manager = getTargetManager(knowledgeBaseName);
        return manager.addDocumentsAsync(filePaths);
    }
    
    public void buildTree(List<Document> documents) {
        buildTree(documents, null);
    }
    
    public void buildTree(List<Document> documents, String knowledgeBaseName) {
        SemanticTreeManager manager = getTargetManager(knowledgeBaseName);
        manager.buildTree(documents);
    }
    
    public SearchResult getSearchResult(String query) {
        return search(query);
    }
    
    public SemanticTreeManager createKnowledgeBase(String name) throws IOException {
        return knowledgeBaseManager.createKnowledgeBase(name);
    }
    
    public void deleteKnowledgeBase(String name) throws IOException {
        knowledgeBaseManager.deleteKnowledgeBase(name);
    }
    
    public void switchKnowledgeBase(String name) throws IOException {
        knowledgeBaseManager.switchKnowledgeBase(name);
    }
    
    public List<String> listKnowledgeBases() {
        return knowledgeBaseManager.listKnowledgeBases();
    }
    
    public SemanticTreeManager getCurrentKnowledgeBase() {
        return knowledgeBaseManager.getCurrentKnowledgeBase();
    }
    
    public boolean knowledgeBaseExists(String name) {
        return knowledgeBaseManager.exists(name);
    }
    
    private SemanticTreeManager getTargetManager(String knowledgeBaseName) {
        if (knowledgeBaseName == null || knowledgeBaseName.trim().isEmpty()) {
            return knowledgeBaseManager.getCurrentKnowledgeBase();
        }
        return knowledgeBaseManager.getKnowledgeBase(knowledgeBaseName);
    }
    
    public void clearCache() {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            cachedProvider.clearCache();
        }
    }
    
    public void clearCache(SmartCacheStrategy.RequestType type) {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            cachedProvider.clearCache(type);
        }
    }
    
    public Map<SmartCacheStrategy.RequestType, CacheStatistics> getCacheStatistics() {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            return cachedProvider.getStatistics();
        }
        return Map.of();
    }
    
    public CacheStatistics getCacheStatistics(SmartCacheStrategy.RequestType type) {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            return cachedProvider.getStatistics(type);
        }
        return null;
    }
    
    public void warmupCache(Map<SmartCacheStrategy.RequestType, List<String>> warmupPrompts) {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            cachedProvider.warmupCache(warmupPrompts);
        }
    }
    
    public void updateCacheConfig(SmartCacheStrategy.RequestType type, CacheConfig config) {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            cachedProvider.updateCacheConfig(type, config);
        }
    }
    
    public long getTotalCacheSize() {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            return cachedProvider.getTotalCacheSize();
        }
        return 0;
    }
    
    public long getCacheSize(SmartCacheStrategy.RequestType type) {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            return cachedProvider.getCacheSize(type);
        }
        return 0;
    }
    
    public void logCacheStatistics() {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            cachedProvider.logStatistics();
        }
    }
    
    public record SearchResult(
        String query,
        List<DocumentChunk> documents,
        String reasoning,
        List<NavigationPath> path
    ) {}
}
