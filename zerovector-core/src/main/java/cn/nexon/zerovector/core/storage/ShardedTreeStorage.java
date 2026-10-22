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
import cn.nexon.zerovector.core.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * 分片语义树存储引擎。
 *
 * <p>每次保存写入一个全新的 generation（文件名带 generation 后缀），最后原子替换
 * {@code metadata.json} 作为提交点。旧 generation 的文件在提交成功后才会删除：
 * 因此中途崩溃时旧 metadata 仍指向完整的旧数据集，不会出现“部分文件已覆盖、
 * 部分仍是旧数据”的损坏状态。
 *
 * <p>读取路径持有读锁，提交只持有短暂的写锁；完整写文件过程不阻塞查询。
 */
public class ShardedTreeStorage implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ShardedTreeStorage.class);
    private static final String METADATA_FILE = "metadata.json";
    private static final String NODE_PREFIX = "node_";
    private static final String CHUNK_PREFIX = "chunk_";
    private static final String ROOT_PREFIX = "root_";
    private static final String ROOT_FILE = "root.json";
    private static final int DEFAULT_SHARD_SIZE = 100;
    private static final int MAX_NODE_CACHE_SIZE = 1000;
    private static final int MAX_CHUNK_CACHE_SIZE = 5000;
    private static final long CACHE_EXPIRE_AFTER_ACCESS_MINUTES = 30;

    private static final TypeReference<Map<String, TreeNode>> NODE_MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, DocumentChunk>> CHUNK_MAP_TYPE = new TypeReference<>() {
    };

    private final Path storageDir;
    private final int shardSize;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final Cache<String, TreeNode> nodeCache;
    private final Cache<String, DocumentChunk> chunkCache;

    /** 当前已提交的索引快照，始终整体替换，读路径无需加锁遍历。 */
    private volatile Map<String, String> nodeShards = Map.of();
    private volatile Map<String, String> chunkShards = Map.of();
    private volatile String rootFile = ROOT_FILE;
    private volatile String generation;
    private volatile TreeNode rootNode;

    public ShardedTreeStorage(String storageDir) {
        this(storageDir, DEFAULT_SHARD_SIZE);
    }

    public ShardedTreeStorage(String storageDir, int shardSize) {
        if (shardSize <= 0) {
            throw new IllegalArgumentException("shardSize must be > 0, got: " + shardSize);
        }
        this.storageDir = Paths.get(storageDir);
        this.shardSize = shardSize;

        this.nodeCache = Caffeine.newBuilder()
                .maximumSize(MAX_NODE_CACHE_SIZE)
                .expireAfterAccess(CACHE_EXPIRE_AFTER_ACCESS_MINUTES, TimeUnit.MINUTES)
                .build();
        this.chunkCache = Caffeine.newBuilder()
                .maximumSize(MAX_CHUNK_CACHE_SIZE)
                .expireAfterAccess(CACHE_EXPIRE_AFTER_ACCESS_MINUTES, TimeUnit.MINUTES)
                .build();

        try {
            Files.createDirectories(this.storageDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create storage directory: " + storageDir, e);
        }
    }

    /** 保存语义树。并发调用会串行化；查询只在提交阶段短暂阻塞。 */
    public synchronized void saveTree(SemanticTree tree) throws IOException {
        if (tree == null) {
            return;
        }

        Map<String, TreeNode> nodes = new HashMap<>(tree.nodes());
        Map<String, DocumentChunk> chunks = new HashMap<>(tree.chunks());

        String newGeneration = UUID.randomUUID().toString().replace("-", "");
        Map<String, String> newNodeShards = saveNodes(nodes, newGeneration);
        Map<String, String> newChunkShards = saveChunks(chunks, newGeneration);
        String newRootFile = saveRoot(tree.rootNode(), newGeneration);

        writeMetadata(new Metadata(newGeneration, newRootFile, newNodeShards, newChunkShards));

        Map<String, String> oldNodeShards;
        Map<String, String> oldChunkShards;
        String oldRootFile;
        lock.writeLock().lock();
        try {
            oldNodeShards = nodeShards;
            oldChunkShards = chunkShards;
            oldRootFile = rootFile;

            nodeShards = Map.copyOf(newNodeShards);
            chunkShards = Map.copyOf(newChunkShards);
            rootFile = newRootFile;
            generation = newGeneration;
            rootNode = tree.rootNode();
        } finally {
            lock.writeLock().unlock();
        }

        deleteGeneration(oldNodeShards.values(), oldChunkShards.values(), oldRootFile, newGeneration);
        logger.debug("语义树已保存, generation={}, nodes={}, chunks={}", newGeneration,
                newNodeShards.size(), newChunkShards.size());
    }

    public SemanticTree loadTree() throws IOException {
        if (!loadMetadata()) {
            cleanupOrphans(null);
            return null;
        }
        loadRootNode();
        cleanupOrphans(generation);
        return new SemanticTree(rootNode, new LazyNodeMap(this), new LazyChunkMap(this));
    }

    /**
     * 获取节点（懒加载）。
     * <p>读锁覆盖“索引定位 + 文件读取”全过程，确保保存方在删除旧 generation 前
     * 不会与正在读取旧文件的查询并发。
     */
    public TreeNode getNode(String nodeId) throws IOException {
        if (nodeId == null) {
            return null;
        }
        lock.readLock().lock();
        try {
            String shardFile = nodeShards.get(nodeId);
            if (shardFile == null) {
                return null;
            }
            TreeNode cached = nodeCache.getIfPresent(nodeId);
            if (cached != null) {
                return cached;
            }
            Map<String, TreeNode> shard = loadNodeShard(shardFile);
            return shard.get(nodeId);
        } finally {
            lock.readLock().unlock();
        }
    }

    public DocumentChunk getChunk(String chunkId) throws IOException {
        if (chunkId == null) {
            return null;
        }
        lock.readLock().lock();
        try {
            String shardFile = chunkShards.get(chunkId);
            if (shardFile == null) {
                return null;
            }
            DocumentChunk cached = chunkCache.getIfPresent(chunkId);
            if (cached != null) {
                return cached;
            }
            Map<String, DocumentChunk> shard = loadChunkShard(shardFile);
            return shard.get(chunkId);
        } finally {
            lock.readLock().unlock();
        }
    }

    private String saveRoot(TreeNode root, String gen) throws IOException {
        if (root == null) {
            return null;
        }
        String fileName = ROOT_PREFIX + gen + ".json";
        Files.writeString(storageDir.resolve(fileName), JsonUtils.toJson(root));
        return fileName;
    }

    private void loadRootNode() throws IOException {
        if (rootFile == null) {
            rootNode = null;
            return;
        }
        Path filePath = storageDir.resolve(rootFile);
        if (!Files.exists(filePath)) {
            throw new IOException("Missing root node file: " + filePath);
        }
        rootNode = JsonUtils.parseJson(Files.readString(filePath), TreeNode.class);
    }

    private Map<String, String> saveNodes(Map<String, TreeNode> nodes, String gen) throws IOException {
        Map<String, String> shardIndex = new HashMap<>();
        List<Map<String, TreeNode>> shards = partition(nodes);
        for (int i = 0; i < shards.size(); i++) {
            String fileName = NODE_PREFIX + gen + "_" + i + ".json";
            Files.writeString(storageDir.resolve(fileName), JsonUtils.toJson(shards.get(i)));
            for (String nodeId : shards.get(i).keySet()) {
                shardIndex.put(nodeId, fileName);
            }
        }
        return shardIndex;
    }

    private Map<String, String> saveChunks(Map<String, DocumentChunk> chunks, String gen) throws IOException {
        Map<String, String> shardIndex = new HashMap<>();
        Map<String, DocumentChunk> metadataOnly = new HashMap<>(chunks.size());
        chunks.forEach((id, chunk) -> metadataOnly.put(id, chunk.metadataOnly()));

        List<Map<String, DocumentChunk>> shards = partition(metadataOnly);
        for (int i = 0; i < shards.size(); i++) {
            String fileName = CHUNK_PREFIX + gen + "_" + i + ".json";
            Files.writeString(storageDir.resolve(fileName), JsonUtils.toJson(shards.get(i)));
            for (String chunkId : shards.get(i).keySet()) {
                shardIndex.put(chunkId, fileName);
            }
        }
        return shardIndex;
    }

    private <T> List<Map<String, T>> partition(Map<String, T> values) {
        List<Map<String, T>> shards = new ArrayList<>();
        Map<String, T> current = new HashMap<>();
        for (Map.Entry<String, T> entry : values.entrySet()) {
            current.put(entry.getKey(), entry.getValue());
            if (current.size() >= shardSize) {
                shards.add(current);
                current = new HashMap<>();
            }
        }
        if (!current.isEmpty()) {
            shards.add(current);
        }
        return shards;
    }

    private Map<String, TreeNode> loadNodeShard(String fileName) throws IOException {
        Path filePath = storageDir.resolve(fileName);
        if (!Files.exists(filePath)) {
            return Map.of();
        }
        Map<String, TreeNode> nodes = JsonUtils.parseJson(Files.readString(filePath), NODE_MAP_TYPE);
        if (nodes == null) {
            return Map.of();
        }
        nodeCache.putAll(nodes);
        return nodes;
    }

    private Map<String, DocumentChunk> loadChunkShard(String fileName) throws IOException {
        Path filePath = storageDir.resolve(fileName);
        if (!Files.exists(filePath)) {
            return Map.of();
        }
        Map<String, DocumentChunk> chunks = JsonUtils.parseJson(Files.readString(filePath), CHUNK_MAP_TYPE);
        if (chunks == null) {
            return Map.of();
        }
        chunkCache.putAll(chunks);
        return chunks;
    }

    private record Metadata(String generation, String rootFile,
                            Map<String, String> nodeShards, Map<String, String> chunkShards) {
    }

    private void writeMetadata(Metadata metadata) throws IOException {
        Path tempPath = storageDir.resolve(METADATA_FILE + ".tmp");
        Files.writeString(tempPath, JsonUtils.toJson(metadata));
        atomicMove(tempPath, storageDir.resolve(METADATA_FILE));
    }

    private boolean loadMetadata() throws IOException {
        Path filePath = storageDir.resolve(METADATA_FILE);
        if (!Files.exists(filePath)) {
            return false;
        }
        Metadata metadata;
        try {
            metadata = JsonUtils.parseJson(Files.readString(filePath), Metadata.class);
        } catch (RuntimeException e) {
            throw new IOException("Corrupt tree metadata: " + filePath, e);
        }
        if (metadata == null || metadata.generation() == null
                || metadata.nodeShards() == null || metadata.chunkShards() == null) {
            throw new IOException("Incomplete tree metadata: " + filePath);
        }
        nodeShards = Map.copyOf(metadata.nodeShards());
        chunkShards = Map.copyOf(metadata.chunkShards());
        rootFile = metadata.rootFile();
        generation = metadata.generation();
        return true;
    }

    private void cleanupOrphans(String currentGeneration) {
        Set<String> keep = new HashSet<>();
        keep.add(METADATA_FILE);
        if (rootFile != null) {
            keep.add(rootFile);
        }
        keep.addAll(nodeShards.values());
        keep.addAll(chunkShards.values());

        try {
            Files.deleteIfExists(storageDir.resolve(METADATA_FILE + ".tmp"));
        } catch (IOException e) {
            logger.warn("清理临时元数据失败: {}", storageDir, e);
        }

        try (Stream<Path> files = Files.list(storageDir)) {
            files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(this::isGenerationFile)
                    .filter(name -> !keep.contains(name))
                    .forEach(this::deleteFile);
        } catch (IOException e) {
            logger.warn("清理孤立分片文件失败: {}", storageDir, e);
        }
    }

    private boolean isGenerationFile(String fileName) {
        if (fileName.equals(METADATA_FILE) || fileName.equals(METADATA_FILE + ".tmp")
                || fileName.equals(ROOT_FILE)) {
            return false;
        }
        return fileName.startsWith(NODE_PREFIX)
                || fileName.startsWith(CHUNK_PREFIX)
                || fileName.startsWith(ROOT_PREFIX);
    }

    private void deleteGeneration(Iterable<String> oldNodeFiles, Iterable<String> oldChunkFiles,
                                  String oldRootFile, String newGeneration) {
        Set<String> toDelete = new HashSet<>();
        oldNodeFiles.forEach(toDelete::add);
        oldChunkFiles.forEach(toDelete::add);
        if (oldRootFile != null) {
            toDelete.add(oldRootFile);
        }
        // 新 generation 的文件绝不能被删除（root 文件名可能与旧值相同）
        if (rootFile != null) {
            toDelete.remove(rootFile);
        }
        toDelete.removeIf(name -> name.contains(newGeneration));
        toDelete.forEach(this::deleteFile);
    }

    private void deleteFile(String fileName) {
        try {
            Files.deleteIfExists(storageDir.resolve(fileName));
        } catch (IOException e) {
            logger.warn("删除分片文件失败: {}", fileName, e);
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void close() {
        nodeCache.invalidateAll();
        chunkCache.invalidateAll();
    }

    public Map<String, String> getNodeShardIndex() {
        return nodeShards;
    }

    public Map<String, String> getChunkShardIndex() {
        return chunkShards;
    }
}
