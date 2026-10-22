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

package cn.nexon.zerovector.core.storage.spi;

import cn.nexon.zerovector.core.exception.StorageException;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.storage.config.ChunkStorageConfig;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 内联正文存储接口。
 *
 * <p>语义树只保存章节元数据；大文件原文由 {@code DocumentSourceStore} 按源文件区间
 * 懒加载，不进入本接口。本接口只负责接口直传小文档的内联正文（默认 mmap）。
 */
public interface ChunkStorage extends AutoCloseable {

    /** 初始化存储。实现必须支持重复调用（第二次为空操作）。 */
    void initialize(ChunkStorageConfig config) throws StorageException;

    /** 保存内联正文；{@code chunk.content()} 为空时只缓存元数据。 */
    void saveChunk(DocumentChunk chunk) throws StorageException;

    /** 批量保存正文。 */
    void saveChunks(Map<String, DocumentChunk> chunks) throws StorageException;

    /**
     * 获取分片元数据；不存在返回 {@code null}。
     * <p>语义树是分片元数据的权威来源；本方法只保证返回当前实现能提供的元数据
     * （本地实现重启后可能只返回 id 占位），正文请使用 {@link #getChunkContent(String)}。
     */
    DocumentChunk getChunk(String chunkId) throws StorageException;

    /** 获取分片正文；不存在返回 {@code null}。 */
    String getChunkContent(String chunkId) throws StorageException;

    void deleteChunk(String chunkId) throws StorageException;

    boolean exists(String chunkId);

    Set<String> getAllChunkIds();

    Map<String, DocumentChunk> getChunks(Collection<String> chunkIds);

    /** 清空所有正文与元数据。 */
    void clear() throws StorageException;

    /** 将待持久化的索引/缓冲刷到磁盘。默认无操作。 */
    default void flush() throws StorageException {
    }

    String getStorageType();

    boolean isHealthy();

    @Override
    void close() throws IOException;
}
