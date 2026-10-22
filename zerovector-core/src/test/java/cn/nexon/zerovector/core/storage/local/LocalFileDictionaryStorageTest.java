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
import cn.nexon.zerovector.core.model.KeywordDefinition;
import cn.nexon.zerovector.core.storage.config.DictionaryStorageConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalFileDictionaryStorageTest {

    @TempDir
    Path tempDir;

    private DictionaryStorageConfig createConfig() {
        return new DictionaryStorageConfig(tempDir.resolve("dictionary.json").toString());
    }

    @Test
    void initialize_isHealthy() {
        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(createConfig());
            assertTrue(storage.isHealthy());
        }
    }

    @Test
    void load_withoutFile_returnsEmptyDictionary() {
        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(createConfig());
            assertEquals(0, storage.load().size());
        }
    }

    @Test
    void saveAndLoad_roundtrip() {
        DictionaryStorageConfig config = createConfig();
        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(config);
            KeywordDictionary dictionary = new KeywordDictionary();
            dictionary.addKeywordDefinition(KeywordDefinition.of("ZeroVector", "A KB", "ctx", "doc1"));
            dictionary.addEntry("zerovector", "node1", 1.5);
            storage.save(dictionary);
        }

        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(config);
            KeywordDictionary loaded = storage.load();
            assertTrue(loaded.containsKeyword("ZeroVector"));
            assertEquals(1, loaded.getNodesForKeyword("zerovector").size());
            assertEquals(1.5, loaded.getKeywordWeight("zerovector"), 0.001);
        }
    }

    @Test
    void save_withoutFilePath_isNoop() {
        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(new DictionaryStorageConfig());
            assertDoesNotThrow(() -> storage.save(new KeywordDictionary()));
        }
    }

    @Test
    void corruptedFile_failsLoudly() throws IOException {
        Path file = tempDir.resolve("corrupt.json");
        Files.writeString(file, "{not json");
        DictionaryStorageConfig config = new DictionaryStorageConfig(file.toString());

        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(config);
            assertThrows(StorageException.class, storage::load);
        }
    }

    @Test
    void notInitialized_throwsException() {
        LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage();
        assertThrows(StorageException.class, storage::load);
        assertThrows(StorageException.class, () -> storage.save(new KeywordDictionary()));
    }

    @Test
    void getStorageType_returnsLocalFile() {
        assertEquals("local-file", new LocalFileDictionaryStorage().getStorageType());
    }
}
