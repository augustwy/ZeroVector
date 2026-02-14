package cn.nexon.zerovector.core.ai;

import cn.nexon.zerovector.core.document.comprehend.DocumentComprehendResult;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.NavigationAction;
import cn.nexon.zerovector.core.model.TreeNode;
import cn.nexon.zerovector.core.tree.TreeBuilder.NodeCategory;
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
    public DocumentComprehendResult comprehendChunk(String prompt, String chunk) {
        return delegate.comprehendChunk(prompt, chunk);
    }
    
    @Override
    public String generateSummary(String title, String content) {
        return stringCache.get("summary:" + title + ":" + content, key -> delegate.generateSummary(title, content));
    }
    
    @Override
    public List<String> extractKeywords(String content) {
        return listCache.get("keywords:" + content, key -> delegate.extractKeywords(content));
    }
    
    @Override
    public List<String> extractEntities(String content) {
        return listCache.get("entities:" + content, key -> delegate.extractEntities(content));
    }
    
    @Override
    public List<String> generateExampleQuestions(String content) {
        return listCache.get("examples:" + content, key -> delegate.generateExampleQuestions(content));
    }
    
    @Override
    public NavigationAction decideNavigation(String query, TreeNode currentNode, List<TreeNode> childNodes) {
        return delegate.decideNavigation(query, currentNode, childNodes);
    }
    
    @Override
    public String generateNodeDescription(TreeNode node, List<String> relatedChunks) {
        return delegate.generateNodeDescription(node, relatedChunks);
    }
    
    @Override
    public List<NodeCategory> clusterChunks(List<DocumentChunk> chunks) {
        return delegate.clusterChunks(chunks);
    }
    
    @Override
    public List<TreeNode> clusterChunks(List<String> chunks, int clusterSize) {
        return delegate.clusterChunks(chunks, clusterSize);
    }
    
    public void clearCache() {
        listCache.invalidateAll();
        stringCache.invalidateAll();
    }
}
