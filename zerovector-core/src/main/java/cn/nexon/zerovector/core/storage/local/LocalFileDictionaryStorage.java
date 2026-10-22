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
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.storage.config.DictionaryStorageConfig;
import cn.nexon.zerovector.core.storage.spi.DictionaryStorage;
import cn.nexon.zerovector.core.storage.spi.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 本地 JSON 文件字典存储。
 *
 * <p>初始化时加载失败会直接失败，而不是静默创建空字典——静默丢索引会让
 * 整个知识库退化为“关键词永远检索不到”，比启动失败更难排查。
 */
@StorageProvider(type = "local-file", priority = 1, description = "本地文件字典存储")
public class LocalFileDictionaryStorage implements DictionaryStorage {

    private static final Logger logger = LoggerFactory.getLogger(LocalFileDictionaryStorage.class);

    private DictionaryStorageConfig config;
    private boolean initialized;

    @Override
    public void initialize(DictionaryStorageConfig config) throws StorageException {
        if (initialized) {
            return;
        }
        this.config = config;
        this.initialized = true;
        logger.debug("LocalFileDictionaryStorage 初始化完成, filePath: {}", config.getFilePath());
    }

    @Override
    public KeywordDictionary load() throws StorageException {
        checkInitialized();
        String filePath = config.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            return new KeywordDictionary();
        }
        try {
            return KeywordDictionary.loadFromFile(filePath);
        } catch (IOException e) {
            throw new StorageException(filePath, "load", StorageException.ERROR_CODE_FILE_CORRUPT,
                    "Failed to load keyword dictionary", e);
        }
    }

    @Override
    public void save(KeywordDictionary dictionary) throws StorageException {
        checkInitialized();
        String filePath = config.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        try {
            dictionary.saveToFile(filePath);
        } catch (IOException e) {
            throw new StorageException(filePath, "save", StorageException.ERROR_CODE_IO_ERROR,
                    "Failed to save keyword dictionary", e);
        }
    }

    @Override
    public String getStorageType() {
        return "local-file";
    }

    @Override
    public boolean isHealthy() {
        return initialized;
    }

    @Override
    public void close() {
        initialized = false;
    }

    private void checkInitialized() {
        if (!initialized) {
            throw new StorageException("dictionary", "access", new IllegalStateException("存储未初始化"));
        }
    }
}
