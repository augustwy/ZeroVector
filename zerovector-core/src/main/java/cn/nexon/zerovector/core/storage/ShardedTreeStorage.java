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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            // [CRITICAL] 必须先物化节点和分片为普通 HashMap，再清空索引。
            // LazyNodeMap/LazyChunkMap 依赖 nodeShards/chunkShards 做懒加载，
            // 如果先 clear 再遍历，懒加载返回空，导致数据丢失。
            Map<String, TreeNode> nodes = new HashMap<>(tree.nodes());
            Map<String, DocumentChunk> chunks = new HashMap<>(tree.chunks());

            // 保存旧引用用于后续清理孤立文件
            Set<String> oldNodeShardFiles = new HashSet<>(nodeShards.values());
            Set<String> oldChunkShardFiles = new HashSet<>(chunkShards.values());

            // 清空现有索引
            nodeShards.clear();
            chunkShards.clear();

            // 写入顺序：数据分片 → 根节点 → 元数据（最后）。
            // metadata 是加载入口，作为提交点：中途崩溃时旧 metadata 仍指向完整旧数据

            // 分片保存节点和文档块
            saveNodesInShards(nodes);
            saveChunksInShards(chunks);

            // 保存根节点
            if (tree.rootNode() != null) {
                saveRootNode(tree.rootNode());
            }

            // 保存元数据（原子替换）
            saveMetadata();

            // 清理不再引用的孤立 shard 文件
            Set<String> newNodeShardFiles = new HashSet<>(nodeShards.values());
            Set<String> newChunkShardFiles = new HashSet<>(chunkShards.values());

            for (String oldFile : oldNodeShardFiles) {
                if (!newNodeShardFiles.contains(oldFile)) {
                    deleteShardFile(oldFile);
                }
            }
            for (String oldFile : oldChunkShardFiles) {
                if (!newChunkShardFiles.contains(oldFile)) {
                    deleteShardFile(oldFile);
                }
            }
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
     * 获取节点（支持懒加载）
     * <p>先检查缓存，未命中则整片加载；即使加载后瞬间被驱逐，
     * 也直接从刚加载的分片数据兜底返回，不会误报节点不存在
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
            Map<String, TreeNode> shard = loadNodeShard(shardFile);
            TreeNode node = nodeCache.getIfPresent(nodeId);
            return node != null ? node : shard.get(nodeId);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load node: " + nodeId, e);
        }
    }
    
    /**
     * 获取文档块（支持懒加载）
     * <p>兜底逻辑同 {@link #getNode}
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
            Map<String, DocumentChunk> shard = loadChunkShard(shardFile);
            DocumentChunk chunk = chunkCache.getIfPresent(chunkId);
            return chunk != null ? chunk : shard.get(chunkId);
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
     * 加载节点分片，返回分片内容（同时写入缓存）
     */
    private Map<String, TreeNode> loadNodeShard(String fileName) throws IOException {
        Path filePath = Paths.get(storageDir, fileName);
        if (!Files.exists(filePath)) {
            return Map.of();
        }
        
        String json = Files.readString(filePath);
        Map<String, TreeNode> nodes = objectMapper.readValue(json, new TypeReference<Map<String, TreeNode>>() {});
        
        nodeCache.putAll(nodes);
        return nodes;
    }
    
    /**
     * 加载文档块分片，返回分片内容（同时写入缓存）
     */
    private Map<String, DocumentChunk> loadChunkShard(String fileName) throws IOException {
        Path filePath = Paths.get(storageDir, fileName);
        if (!Files.exists(filePath)) {
            return Map.of();
        }
        
        String json = Files.readString(filePath);
        Map<String, DocumentChunk> chunks = objectMapper.readValue(json, new TypeReference<Map<String, DocumentChunk>>() {});
        
        chunkCache.putAll(chunks);
        return chunks;
    }
    
    /**
     * 保存元数据
     * <p>元数据是加载入口（commit marker），必须最后写入且原子替换：
     * 先写临时文件再 move，崩溃时旧 metadata 仍指向一致的数据集
     */
    private void saveMetadata() throws IOException {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("nodeShards", nodeShards);
        metadata.put("chunkShards", chunkShards);
        metadata.put("shardSize", shardSize);
        
        String json = objectMapper.writeValueAsString(metadata);
        Path filePath = Paths.get(storageDir, METADATA_FILE);
        Path tempPath = Paths.get(storageDir, METADATA_FILE + ".tmp");
        Files.writeString(tempPath, json);
        Files.move(tempPath, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
    
    /**
     * 删除分片文件
     */
    private void deleteShardFile(String fileName) {
        try {
            Path filePath = Paths.get(storageDir, fileName);
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            logger.warn("清理孤立分片文件失败: {}", fileName, e);
        }
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