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
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.storage.config.DictionaryStorageConfig;

import java.io.IOException;

/**
 * 关键词字典持久化接口。
 *
 * <p>字典是内存中的倒排索引，本接口只负责整体加载与保存，避免在 SPI 层
 * 复制一套与 {@link KeywordDictionary} 重叠的增删改方法。
 */
public interface DictionaryStorage extends AutoCloseable {

    void initialize(DictionaryStorageConfig config) throws StorageException;

    /** 加载字典；不存在时返回空字典。 */
    KeywordDictionary load() throws StorageException;

    /** 整体保存字典（实现应保证原子替换）。 */
    void save(KeywordDictionary dictionary) throws StorageException;

    String getStorageType();

    boolean isHealthy();

    @Override
    void close() throws IOException;
}
