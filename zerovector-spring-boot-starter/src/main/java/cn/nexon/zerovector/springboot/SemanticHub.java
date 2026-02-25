package cn.nexon.zerovector.springboot;

import cn.nexon.zerovector.core.KnowledgeBaseManager;
import cn.nexon.zerovector.core.SemanticTreeManager;
import cn.nexon.zerovector.core.ai.CacheConfig;
import cn.nexon.zerovector.core.ai.CacheStatistics;
import cn.nexon.zerovector.core.ai.CachedLLMProvider;
import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.core.model.Document;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.DocumentUploadResult;
import cn.nexon.zerovector.core.model.NavigationPath;
import cn.nexon.zerovector.core.model.NavigationResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * ZeroVector 语义中心
 * 提供文档上传、搜索、知识库管理等核心功能的统一入口
 */
@Component
public class SemanticHub {
    
    private final KnowledgeBaseManager knowledgeBaseManager;
    
    public SemanticHub(KnowledgeBaseManager knowledgeBaseManager) {
        this.knowledgeBaseManager = knowledgeBaseManager;
    }
    
    /**
     * 执行搜索
     *
     * @param query 查询字符串
     * @return 搜索结果
     */
    public SearchResult search(String query) {
        return search(query, null);
    }
    
    /**
     * 执行搜索
     *
     * @param query 查询字符串
     * @param knowledgeBaseName 知识库名称
     * @return 搜索结果
     */
    public SearchResult search(String query, String knowledgeBaseName) {
        SemanticTreeManager manager = getTargetManager(knowledgeBaseName);
        NavigationResult result = manager.navigate(query);
        
        return new SearchResult(
            query,
            result.documents(),
            result.reasoning(),
            result.path(),
            result.llmUsageStats()
        );
    }
    
    /**
     * 添加文档
     *
     * @param filePath 文件路径
     * @return 文档上传结果，包含 LLM 调用统计
     */
    public DocumentUploadResult addDocument(Path filePath) {
        return addDocument(filePath, null);
    }
    
    /**
     * 添加文档
     *
     * @param filePath 文件路径
     * @param knowledgeBaseName 知识库名称
     * @return 文档上传结果，包含 LLM 调用统计
     */
    public DocumentUploadResult addDocument(Path filePath, String knowledgeBaseName) {
        SemanticTreeManager manager = getTargetManager(knowledgeBaseName);
        return manager.addDocument(filePath);
    }
    
    /**
     * 异步添加文档
     *
     * @param filePath 文件路径
     * @return 异步结果
     */
    public CompletableFuture<DocumentUploadResult> addDocumentAsync(Path filePath) {
        return addDocumentAsync(filePath, null);
    }
    
    /**
     * 异步添加文档
     *
     * @param filePath 文件路径
     * @param knowledgeBaseName 知识库名称
     * @return 异步结果
     */
    public CompletableFuture<DocumentUploadResult> addDocumentAsync(Path filePath, String knowledgeBaseName) {
        SemanticTreeManager manager = getTargetManager(knowledgeBaseName);
        return CompletableFuture.supplyAsync(() -> manager.addDocument(filePath));
    }
    
    /**
     * 批量添加文档
     *
     * @param filePaths 文件路径列表
     * @return 每个文档的上传结果列表，包含 LLM 调用统计
     */
    public List<DocumentUploadResult> addDocuments(List<Path> filePaths) {
        return addDocuments(filePaths, null);
    }
    
    /**
     * 批量添加文档
     *
     * @param filePaths 文件路径列表
     * @param knowledgeBaseName 知识库名称
     * @return 每个文档的上传结果列表，包含 LLM 调用统计
     */
    public List<DocumentUploadResult> addDocuments(List<Path> filePaths, String knowledgeBaseName) {
        SemanticTreeManager manager = getTargetManager(knowledgeBaseName);
        return manager.addDocuments(filePaths);
    }
    
    /**
     * 异步批量添加文档
     *
     * @param filePaths 文件路径列表
     * @return 异步结果
     */
    public CompletableFuture<List<DocumentUploadResult>> addDocumentsAsync(List<Path> filePaths) {
        return addDocumentsAsync(filePaths, null);
    }
    
    /**
     * 异步批量添加文档
     *
     * @param filePaths 文件路径列表
     * @param knowledgeBaseName 知识库名称
     * @return 异步结果
     */
    public CompletableFuture<List<DocumentUploadResult>> addDocumentsAsync(List<Path> filePaths, String knowledgeBaseName) {
        SemanticTreeManager manager = getTargetManager(knowledgeBaseName);
        return manager.addDocumentsAsync(filePaths);
    }
    
    /**
     * 构建语义树
     *
     * @param documents 文档列表
     */
    public void buildTree(List<Document> documents) {
        buildTree(documents, null);
    }
    
    /**
     * 构建语义树
     *
     * @param documents 文档列表
     * @param knowledgeBaseName 知识库名称
     */
    public void buildTree(List<Document> documents, String knowledgeBaseName) {
        SemanticTreeManager manager = getTargetManager(knowledgeBaseName);
        manager.buildTree(documents);
    }
    
    /**
     * 获取搜索结果
     *
     * @param query 查询字符串
     * @return 搜索结果
     */
    public SearchResult getSearchResult(String query) {
        return search(query);
    }
    
    /**
     * 创建知识库
     *
     * @param name 知识库名称
     * @return 语义树管理器
     * @throws IOException IO异常
     */
    public SemanticTreeManager createKnowledgeBase(String name) throws IOException {
        return knowledgeBaseManager.createKnowledgeBase(name);
    }
    
    /**
     * 删除知识库
     *
     * @param name 知识库名称
     * @throws IOException IO异常
     */
    public void deleteKnowledgeBase(String name) throws IOException {
        knowledgeBaseManager.deleteKnowledgeBase(name);
    }
    
    /**
     * 切换知识库
     *
     * @param name 知识库名称
     * @throws IOException IO异常
     */
    public void switchKnowledgeBase(String name) throws IOException {
        knowledgeBaseManager.switchKnowledgeBase(name);
    }
    
    /**
     * 列出所有知识库
     *
     * @return 知识库名称列表
     */
    public List<String> listKnowledgeBases() {
        return knowledgeBaseManager.listKnowledgeBases();
    }
    
    /**
     * 获取当前知识库
     *
     * @return 语义树管理器
     */
    public SemanticTreeManager getCurrentKnowledgeBase() {
        return knowledgeBaseManager.getCurrentKnowledgeBase();
    }
    
    /**
     * 检查知识库是否存在
     *
     * @param name 知识库名称
     * @return 是否存在
     */
    public boolean knowledgeBaseExists(String name) {
        return knowledgeBaseManager.exists(name);
    }
    
    private SemanticTreeManager getTargetManager(String knowledgeBaseName) {
        if (knowledgeBaseName == null || knowledgeBaseName.trim().isEmpty()) {
            return knowledgeBaseManager.getCurrentKnowledgeBase();
        }
        return knowledgeBaseManager.getKnowledgeBase(knowledgeBaseName);
    }
    
    /**
     * 清除所有缓存
     */
    public void clearCache() {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            cachedProvider.clearCache();
        }
    }
    
    /**
     * 清除指定类型的缓存
     *
     * @param type 缓存类型
     */
    public void clearCache(SmartCacheStrategy.RequestType type) {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            cachedProvider.clearCache(type);
        }
    }
    
    /**
     * 获取所有缓存统计
     *
     * @return 缓存统计映射
     */
    public Map<SmartCacheStrategy.RequestType, CacheStatistics> getCacheStatistics() {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            return cachedProvider.getStatistics();
        }
        return Map.of();
    }
    
    /**
     * 获取指定类型的缓存统计
     *
     * @param type 缓存类型
     * @return 缓存统计
     */
    public CacheStatistics getCacheStatistics(SmartCacheStrategy.RequestType type) {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            return cachedProvider.getStatistics(type);
        }
        return null;
    }
    
    /**
     * 预热缓存
     *
     * @param warmupPrompts 预热提示词映射
     */
    public void warmupCache(Map<SmartCacheStrategy.RequestType, List<String>> warmupPrompts) {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            cachedProvider.warmupCache(warmupPrompts);
        }
    }
    
    /**
     * 更新缓存配置
     *
     * @param type 缓存类型
     * @param config 缓存配置
     */
    public void updateCacheConfig(SmartCacheStrategy.RequestType type, CacheConfig config) {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            cachedProvider.updateCacheConfig(type, config);
        }
    }
    
    /**
     * 获取总缓存大小
     *
     * @return 缓存大小
     */
    public long getTotalCacheSize() {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            return cachedProvider.getTotalCacheSize();
        }
        return 0;
    }
    
    /**
     * 获取指定类型的缓存大小
     *
     * @param type 缓存类型
     * @return 缓存大小
     */
    public long getCacheSize(SmartCacheStrategy.RequestType type) {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            return cachedProvider.getCacheSize(type);
        }
        return 0;
    }
    
    /**
     * 记录缓存统计日志
     */
    public void logCacheStatistics() {
        if (knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cachedProvider) {
            cachedProvider.logStatistics();
        }
    }
    
    /**
     * 搜索结果
     * 包含查询结果和 LLM 调用统计信息
     *
     * @param query 查询字符串
     * @param documents 文档列表
     * @param reasoning 推理说明
     * @param path 导航路径
     * @param llmUsageStats LLM 调用统计
     */
    public record SearchResult(
        String query,
        List<DocumentChunk> documents,
        String reasoning,
        List<NavigationPath> path,
        LLMUsageStats llmUsageStats
    ) {}
}
