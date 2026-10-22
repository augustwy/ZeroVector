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

package cn.nexon.zerovector.core.storage.local;

import cn.nexon.zerovector.core.exception.StorageException;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.storage.ShardedMMapStore;
import cn.nexon.zerovector.core.storage.config.ChunkStorageConfig;
import cn.nexon.zerovector.core.storage.spi.ChunkStorage;
import cn.nexon.zerovector.core.storage.spi.StorageProvider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 本地文件正文存储。
 *
 * <p>正文写入 {@link ShardedMMapStore}（按 chunkId 哈希分片），内存中仅保留
 * 最近访问的分片元数据缓存。语义树负责保存摘要等元数据，本实现只负责正文，
 * 避免同一份正文在树文件里重复落盘。
 */
@StorageProvider(type = "local-mmap", priority = 1, description = "本地文件存储（基于 mmap）")
public class LocalFileChunkStorage implements ChunkStorage {

    private static final Logger logger = LoggerFactory.getLogger(LocalFileChunkStorage.class);

    private ShardedMMapStore mmapStore;
    private ChunkStorageConfig config;
    private boolean initialized;

    // 导航路径会并发读取，必须使用并发容器；限制容量避免大知识库常驻内存
    private final Cache<String, DocumentChunk> chunkCache = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build();

    @Override
    public void initialize(ChunkStorageConfig config) throws StorageException {
        if (initialized) {
            return;
        }
        if (config == null || config.getBasePath() == null || config.getBasePath().isBlank()) {
            throw new StorageException("chunk", "initialize",
                    new IllegalArgumentException("basePath must not be empty"));
        }

        this.config = config;

        try {
            Path basePath = Paths.get(config.getBasePath()).toAbsolutePath();
            Path parent = basePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            if (config.isUseMmap()) {
                int shardCount = Math.max(1, config.getMmapShardCount());
                this.mmapStore = ShardedMMapStore.open(basePath.toString(), shardCount);
                logger.debug("已初始化分片 mmap 存储: basePath={}, shards={}", basePath, shardCount);
            }

            initialized = true;
            logger.debug("LocalFileChunkStorage 初始化完成, basePath: {}", basePath);
        } catch (IOException e) {
            throw new StorageException(config.getBasePath(), "initialize", e);
        }
    }

    @Override
    public void saveChunk(DocumentChunk chunk) throws StorageException {
        checkInitialized();
        if (chunk == null || chunk.id() == null || chunk.id().isBlank()) {
            throw new IllegalArgumentException("chunk id must not be empty");
        }

        if (mmapStore != null && chunk.hasContent()) {
            try {
                mmapStore.addChunk(chunk.id(), chunk.content());
            } catch (IOException e) {
                throw new StorageException(chunk.id(), "saveChunk", e);
            }
            // 只缓存元数据，正文按需从 mmap 读取，避免常驻内存
            chunkCache.put(chunk.id(), DocumentChunk.metadataOnly(
                    chunk.id(), chunk.summary(), chunk.md5(), chunk.metadata()));
        } else {
            chunkCache.put(chunk.id(), chunk);
        }
        logger.debug("保存文档分片: {}", chunk.id());
    }

    @Override
    public void saveChunks(Map<String, DocumentChunk> chunks) throws StorageException {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (DocumentChunk chunk : chunks.values()) {
            saveChunk(chunk);
        }
        logger.debug("批量保存文档分片: {} 个", chunks.size());
    }

    @Override
    public DocumentChunk getChunk(String chunkId) throws StorageException {
        checkInitialized();
        if (chunkId == null || chunkId.isEmpty()) {
            return null;
        }

        DocumentChunk cached = chunkCache.getIfPresent(chunkId);
        if (cached != null) {
            return cached;
        }

        if (mmapStore != null && mmapStore.containsChunk(chunkId)) {
            // 正文可读，但摘要等元数据由语义树持有，这里只返回占位元数据
            return DocumentChunk.metadataOnly(chunkId, "", null, Map.of());
        }
        return null;
    }

    @Override
    public String getChunkContent(String chunkId) throws StorageException {
        checkInitialized();
        if (chunkId == null || chunkId.isEmpty()) {
            return null;
        }

        DocumentChunk cached = chunkCache.getIfPresent(chunkId);
        if (cached != null && cached.hasContent()) {
            return cached.content();
        }
        if (mmapStore != null) {
            return mmapStore.getChunk(chunkId);
        }
        return null;
    }

    @Override
    public void deleteChunk(String chunkId) throws StorageException {
        checkInitialized();
        if (chunkId == null || chunkId.isEmpty()) {
            return;
        }
        chunkCache.invalidate(chunkId);
        if (mmapStore != null) {
            mmapStore.deleteChunk(chunkId);
        }
    }

    @Override
    public boolean exists(String chunkId) {
        if (!initialized || chunkId == null || chunkId.isEmpty()) {
            return false;
        }
        return chunkCache.asMap().containsKey(chunkId)
                || (mmapStore != null && mmapStore.containsChunk(chunkId));
    }

    @Override
    public Set<String> getAllChunkIds() {
        if (!initialized) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>(chunkCache.asMap().keySet());
        if (mmapStore != null) {
            ids.addAll(mmapStore.getAllChunkIds());
        }
        return Collections.unmodifiableSet(ids);
    }

    @Override
    public Map<String, DocumentChunk> getChunks(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Map.of();
        }
        Map<String, DocumentChunk> result = new HashMap<>();
        for (String chunkId : chunkIds) {
            DocumentChunk chunk = getChunk(chunkId);
            if (chunk != null) {
                result.put(chunkId, chunk);
            }
        }
        return result;
    }

    @Override
    public void clear() throws StorageException {
        checkInitialized();
        chunkCache.invalidateAll();
        if (mmapStore != null) {
            try {
                mmapStore.clear();
            } catch (IOException e) {
                throw new StorageException(config.getBasePath(), "clear", e);
            }
        }
        logger.debug("清空所有文档分片");
    }

    @Override
    public void flush() throws StorageException {
        checkInitialized();
        if (mmapStore != null) {
            try {
                mmapStore.flush();
            } catch (IOException e) {
                throw new StorageException(config.getBasePath(), "flush", e);
            }
        }
    }

    @Override
    public String getStorageType() {
        return "local-mmap";
    }

    @Override
    public boolean isHealthy() {
        return initialized && (mmapStore != null || !config.isUseMmap());
    }

    @Override
    public void close() throws IOException {
        if (mmapStore != null) {
            mmapStore.close();
            mmapStore = null;
        }
        chunkCache.invalidateAll();
        initialized = false;
        logger.debug("LocalFileChunkStorage 已关闭");
    }

    private void checkInitialized() {
        if (!initialized) {
            throw new StorageException("chunk", "access", new IllegalStateException("存储未初始化"));
        }
    }
}
