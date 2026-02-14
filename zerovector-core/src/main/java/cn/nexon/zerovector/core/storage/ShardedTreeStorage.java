package cn.nexon.zerovector.core.storage;

import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.SemanticTree;
import cn.nexon.zerovector.core.model.TreeNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分片语义树存储引擎
 * 将大型语义树拆分为多个文件存储，提高加载和查询性能
 */
public class ShardedTreeStorage implements AutoCloseable {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String METADATA_FILE = "metadata.json";
    private static final String NODE_PREFIX = "node_";
    private static final String CHUNK_PREFIX = "chunk_";
    private static final int DEFAULT_SHARD_SIZE = 100;
    private static final int MAX_NODE_CACHE_SIZE = 1000;
    private static final int MAX_CHUNK_CACHE_SIZE = 5000;
    private static final long CACHE_EXPIRE_AFTER_ACCESS_MINUTES = 30;
    private static final Logger logger = LoggerFactory.getLogger(ShardedTreeStorage.class);
    
    private final String storageDir;
    private final int shardSize;
    
    private TreeNode rootNode;
    private final Cache<String, TreeNode> nodeCache;
    private final Cache<String, DocumentChunk> chunkCache;
    
    // 分片索引
    private final Map<String, String> nodeShards = new ConcurrentHashMap<>();
    private final Map<String, String> chunkShards = new ConcurrentHashMap<>();
    
    public ShardedTreeStorage(String storageDir) {
        this(storageDir, DEFAULT_SHARD_SIZE);
    }
    
    public ShardedTreeStorage(String storageDir, int shardSize) {
        this.storageDir = storageDir;
        this.shardSize = shardSize;
        
        this.nodeCache = Caffeine.newBuilder()
            .maximumSize(MAX_NODE_CACHE_SIZE)
            .expireAfterAccess(CACHE_EXPIRE_AFTER_ACCESS_MINUTES, java.util.concurrent.TimeUnit.MINUTES)
            .build();
        
        this.chunkCache = Caffeine.newBuilder()
            .maximumSize(MAX_CHUNK_CACHE_SIZE)
            .expireAfterAccess(CACHE_EXPIRE_AFTER_ACCESS_MINUTES, java.util.concurrent.TimeUnit.MINUTES)
            .build();
        
        try {
            Files.createDirectories(Paths.get(storageDir));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create storage directory", e);
        }
    }
    
    /**
     * 保存语义树到分片文件
     */
    public void saveTree(SemanticTree tree) throws IOException {
        synchronized (this) {
            // 清空现有索引
            nodeShards.clear();
            chunkShards.clear();
            
            // 保存根节点
            if (tree.rootNode() != null) {
                saveRootNode(tree.rootNode());
            }
            
            // 分片保存节点
            saveNodesInShards(tree.nodes());
            
            // 分片保存文档块
            saveChunksInShards(tree.chunks());
            
            // 保存元数据
            saveMetadata();
        }
    }
    
    /**
     * 从分片文件加载语义树
     */
    public SemanticTree loadTree() throws IOException {
        if (!loadMetadata()) {
            return null;
        }
        
        loadRootNode();
        
        return new SemanticTree(rootNode, new LazyNodeMap(this), new LazyChunkMap(this));
    }
    
    /**
     * 增量更新语义树
     * 只保存新增的节点和文档块，提高效率
     */
    public void updateTreeIncremental(SemanticTree oldTree, SemanticTree newTree) throws IOException {
        if (oldTree == null) {
            throw new IllegalArgumentException("Old tree cannot be null");
        }
        if (newTree == null) {
            throw new IllegalArgumentException("New tree cannot be null");
        }
        
        TreeNode oldRoot = oldTree.rootNode();
        TreeNode newRoot = newTree.rootNode();
        
        if (oldRoot == null && newRoot == null) {
            return;
        }
        
        if (oldRoot == null || !oldRoot.equals(newRoot)) {
            if (newRoot != null) {
                saveRootNode(newRoot);
            }
        }
        
        Map<String, TreeNode> newNodes = new HashMap<>();
        for (Map.Entry<String, TreeNode> entry : newTree.nodes().entrySet()) {
            if (!oldTree.nodes().containsKey(entry.getKey())) {
                newNodes.put(entry.getKey(), entry.getValue());
            }
        }
        
        Map<String, DocumentChunk> newChunks = new HashMap<>();
        for (Map.Entry<String, DocumentChunk> entry : newTree.chunks().entrySet()) {
            if (!oldTree.chunks().containsKey(entry.getKey())) {
                newChunks.put(entry.getValue().id(), entry.getValue());
            }
        }
        
        if (!newNodes.isEmpty()) {
            saveNodesInShards(newNodes);
        }
        
        if (!newChunks.isEmpty()) {
            saveChunksInShards(newChunks);
        }
        
        saveMetadata();
    }
    
    /**
     * 获取节点（支持懒加载）
     */
    public TreeNode getNode(String nodeId) throws IOException {
        String shardFile = nodeShards.get(nodeId);
        if (shardFile == null) {
            return null;
        }
        
        TreeNode cached = nodeCache.getIfPresent(nodeId);
        if (cached != null) {
            return cached;
        }
        
        try {
            loadNodeShard(shardFile);
            return nodeCache.getIfPresent(nodeId);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load node: " + nodeId, e);
        }
    }
    
    /**
     * 获取文档块（支持懒加载）
     */
    public DocumentChunk getChunk(String chunkId) throws IOException {
        String shardFile = chunkShards.get(chunkId);
        if (shardFile == null) {
            return null;
        }
        
        DocumentChunk cached = chunkCache.getIfPresent(chunkId);
        if (cached != null) {
            return cached;
        }
        
        try {
            loadChunkShard(shardFile);
            return chunkCache.getIfPresent(chunkId);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load chunk: " + chunkId, e);
        }
    }
    
    /**
     * 保存根节点
     */
    private void saveRootNode(TreeNode rootNode) throws IOException {
        Path filePath = Paths.get(storageDir, "root.json");
        String json = objectMapper.writeValueAsString(rootNode);
        Files.writeString(filePath, json);
    }
    
    /**
     * 加载根节点
     */
    private void loadRootNode() throws IOException {
        Path filePath = Paths.get(storageDir, "root.json");
        if (!Files.exists(filePath)) {
            return;
        }
        
        String json = Files.readString(filePath);
        this.rootNode = objectMapper.readValue(json, TreeNode.class);
    }
    
    /**
     * 分片保存节点
     */
    private void saveNodesInShards(Map<String, TreeNode> nodes) throws IOException {
        int shardIndex = 0;
        int countInCurrentShard = 0;
        Map<String, TreeNode> currentShard = new HashMap<>();
        
        for (Map.Entry<String, TreeNode> entry : nodes.entrySet()) {
            currentShard.put(entry.getKey(), entry.getValue());
            nodeShards.put(entry.getKey(), NODE_PREFIX + shardIndex + ".json");
            countInCurrentShard++;
            
            // 达到分片大小，保存当前分片
            if (countInCurrentShard >= shardSize) {
                saveNodeShard(NODE_PREFIX + shardIndex + ".json", currentShard);
                currentShard.clear();
                shardIndex++;
                countInCurrentShard = 0;
            }
        }
        
        // 保存最后一个分片
        if (!currentShard.isEmpty()) {
            saveNodeShard(NODE_PREFIX + shardIndex + ".json", currentShard);
        }
    }
    
    /**
     * 分片保存文档块
     */
    private void saveChunksInShards(Map<String, DocumentChunk> chunks) throws IOException {
        int shardIndex = 0;
        int countInCurrentShard = 0;
        Map<String, DocumentChunk> currentShard = new HashMap<>();
        
        for (Map.Entry<String, DocumentChunk> entry : chunks.entrySet()) {
            DocumentChunk chunk = entry.getValue();
            // 如果是基于文件路径的文档块，创建一个新的DocumentChunk，其中content为null
            if (chunk.isFilePathBased()) {
                chunk = new DocumentChunk(
                    chunk.id(),
                    null,  // 基于文件路径的文档块不保存content
                    chunk.summary(),
                    chunk.filePath(),
                    chunk.md5(),
                    chunk.metadata()
                );
            }
            
            currentShard.put(entry.getKey(), chunk);
            chunkShards.put(entry.getKey(), CHUNK_PREFIX + shardIndex + ".json");
            countInCurrentShard++;
            
            // 达到分片大小，保存当前分片
            if (countInCurrentShard >= shardSize) {
                saveChunkShard(CHUNK_PREFIX + shardIndex + ".json", currentShard);
                currentShard.clear();
                shardIndex++;
                countInCurrentShard = 0;
            }
        }
        
        // 保存最后一个分片
        if (!currentShard.isEmpty()) {
            saveChunkShard(CHUNK_PREFIX + shardIndex + ".json", currentShard);
        }
    }
    
    /**
     * 保存节点分片
     */
    private void saveNodeShard(String fileName, Map<String, TreeNode> nodes) throws IOException {
        Path filePath = Paths.get(storageDir, fileName);
        String json = objectMapper.writeValueAsString(nodes);
        Files.writeString(filePath, json);
    }
    
    /**
     * 保存文档块分片
     */
    private void saveChunkShard(String fileName, Map<String, DocumentChunk> chunks) throws IOException {
        Path filePath = Paths.get(storageDir, fileName);
        String json = objectMapper.writeValueAsString(chunks);
        Files.writeString(filePath, json);
    }
    
    /**
     * 加载节点分片
     */
    private void loadNodeShard(String fileName) throws IOException {
        Path filePath = Paths.get(storageDir, fileName);
        if (!Files.exists(filePath)) {
            return;
        }
        
        String json = Files.readString(filePath);
        Map<String, TreeNode> nodes = objectMapper.readValue(json, new TypeReference<Map<String, TreeNode>>() {});
        
        for (Map.Entry<String, TreeNode> entry : nodes.entrySet()) {
            nodeCache.put(entry.getKey(), entry.getValue());
        }
    }
    
    /**
     * 加载文档块分片
     */
    private void loadChunkShard(String fileName) throws IOException {
        Path filePath = Paths.get(storageDir, fileName);
        if (!Files.exists(filePath)) {
            return;
        }
        
        String json = Files.readString(filePath);
        Map<String, DocumentChunk> chunks = objectMapper.readValue(json, new TypeReference<Map<String, DocumentChunk>>() {});
        
        for (Map.Entry<String, DocumentChunk> entry : chunks.entrySet()) {
            chunkCache.put(entry.getKey(), entry.getValue());
        }
    }
    
    /**
     * 加载所有节点
     */
    private void loadAllNodes() throws IOException {
        // 遍历所有节点分片文件
        Files.list(Paths.get(storageDir))
            .filter(path -> path.getFileName().toString().startsWith(NODE_PREFIX))
            .forEach(path -> {
                try {
                    loadNodeShard(path.getFileName().toString());
                } catch (IOException e) {
                    logger.error("Failed to load node shard: {}, error: {}", path, e.getMessage());
                }
            });
    }
    
    /**
     * 加载所有文档块
     */
    private void loadAllChunks() throws IOException {
        // 遍历所有文档块分片文件
        Files.list(Paths.get(storageDir))
            .filter(path -> path.getFileName().toString().startsWith(CHUNK_PREFIX))
            .forEach(path -> {
                try {
                    loadChunkShard(path.getFileName().toString());
                } catch (IOException e) {
                    logger.error("Failed to load chunk shard: {}, error: {}", path, e.getMessage());
                }
            });
    }
    
    /**
     * 保存元数据
     */
    private void saveMetadata() throws IOException {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("nodeShards", nodeShards);
        metadata.put("chunkShards", chunkShards);
        metadata.put("shardSize", shardSize);
        
        Path filePath = Paths.get(storageDir, METADATA_FILE);
        String json = objectMapper.writeValueAsString(metadata);
        Files.writeString(filePath, json);
    }
    
    /**
     * 加载元数据
     */
    private boolean loadMetadata() throws IOException {
        Path filePath = Paths.get(storageDir, METADATA_FILE);
        if (!Files.exists(filePath)) {
            return false;
        }
        
        String json = Files.readString(filePath);
        Map<String, Object> metadata = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        
        @SuppressWarnings("unchecked")
        Map<String, String> nodeShardMap = (Map<String, String>) metadata.get("nodeShards");
        if (nodeShardMap != null) {
            nodeShards.putAll(nodeShardMap);
        }
        
        @SuppressWarnings("unchecked")
        Map<String, String> chunkShardMap = (Map<String, String>) metadata.get("chunkShards");
        if (chunkShardMap != null) {
            chunkShards.putAll(chunkShardMap);
        }
        
        return true;
    }
    
    @Override
    public void close() throws IOException {
        nodeCache.invalidateAll();
        chunkCache.invalidateAll();
    }
    
    public Map<String, String> getNodeShardIndex() {
        return Collections.unmodifiableMap(nodeShards);
    }
    
    public Map<String, String> getChunkShardIndex() {
        return Collections.unmodifiableMap(chunkShards);
    }
}