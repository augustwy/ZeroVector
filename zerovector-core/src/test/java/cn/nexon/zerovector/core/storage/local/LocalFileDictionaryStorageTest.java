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
import cn.nexon.zerovector.core.model.KeywordDefinition;
import cn.nexon.zerovector.core.storage.config.DictionaryStorageConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalFileDictionaryStorageTest {

    @TempDir
    Path tempDir;

    private DictionaryStorageConfig createConfig() {
        DictionaryStorageConfig config = new DictionaryStorageConfig();
        config.setFilePath(tempDir.resolve("test.dict").toString());
        return config;
    }

    @Test
    void initialize_createsDictionary() throws IOException {
        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(createConfig());
            assertTrue(storage.isHealthy());
            assertEquals(0, storage.size());
        }
    }

    @Test
    void saveAndGetKeywordDefinition() throws IOException {
        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(createConfig());
            KeywordDefinition def = KeywordDefinition.of("ZeroVector", "A KB system", "doc context", "doc1");
            storage.saveKeywordDefinition(def);

            var retrieved = storage.getKeywordDefinition("ZeroVector");
            assertTrue(retrieved.isPresent());
            assertEquals("A KB system", retrieved.get().definition());
            assertEquals("doc1", retrieved.get().documentId());
        }
    }

    @Test
    void saveKeywordDefinitions_batch() throws IOException {
        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(createConfig());
            List<KeywordDefinition> defs = List.of(
                KeywordDefinition.of("key1", "def1", "ctx1", "doc1"),
                KeywordDefinition.of("key2", "def2", "ctx2", "doc2")
            );
            storage.saveKeywordDefinitions(defs);
            assertEquals(2, storage.size());
        }
    }

    @Test
    void getKeywordDefinition_nonexistent_returnsEmpty() throws IOException {
        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(createConfig());
            assertTrue(storage.getKeywordDefinition("nonexistent").isEmpty());
        }
    }

    @Test
    void deleteKeywordDefinition() throws IOException {
        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(createConfig());
            KeywordDefinition def = KeywordDefinition.of("delete_me", "def", "ctx", "doc1");
            storage.saveKeywordDefinition(def);
            assertTrue(storage.containsKeyword("delete_me"));

            storage.deleteKeywordDefinition("delete_me");
            assertFalse(storage.containsKeyword("delete_me"));
        }
    }

    @Test
    void addInvertedIndexEntry() throws IOException {
        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(createConfig());
            storage.addInvertedIndexEntry("keyword1", "node1", 1.5);
            List<String> nodes = storage.getNodesForKeyword("keyword1");
            assertEquals(1, nodes.size());
            assertTrue(nodes.contains("node1"));
        }
    }

    @Test
    void addInvertedIndexEntries_batch() throws IOException {
        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(createConfig());
            storage.addInvertedIndexEntries("keyword1", List.of("node1", "node2", "node3"));
            List<String> nodes = storage.getNodesForKeyword("keyword1");
            assertEquals(3, nodes.size());
        }
    }

    @Test
    void matchCandidates_returnsWeightedResults() throws IOException {
        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(createConfig());
            storage.addInvertedIndexEntry("zerovector", "node1", 1.0);
            storage.addInvertedIndexEntry("knowledge", "node1", 0.5);

            Map<String, Double> candidates = storage.matchCandidates("zerovector knowledge base");
            assertFalse(candidates.isEmpty());
            assertTrue(candidates.containsKey("node1"));
        }
    }

    @Test
    void matchCandidatesFromKeywords() throws IOException {
        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(createConfig());
            storage.addInvertedIndexEntry("test", "node1", 1.0);
            storage.addInvertedIndexEntry("keyword", "node2", 2.0);

            Map<String, Double> candidates = storage.matchCandidatesFromKeywords(List.of("test"));
            assertEquals(1, candidates.size());
            assertTrue(candidates.containsKey("node1"));
        }
    }

    @Test
    void persist_and_reload() throws IOException {
        String dictPath = tempDir.resolve("persist.dict").toString();

        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            DictionaryStorageConfig config = new DictionaryStorageConfig(dictPath);
            storage.initialize(config);
            storage.saveKeywordDefinition(KeywordDefinition.of("persistent", "value", "ctx", "doc1"));
            storage.persist();
        }

        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            DictionaryStorageConfig config = new DictionaryStorageConfig(dictPath);
            storage.initialize(config);
            var retrieved = storage.getKeywordDefinition("persistent");
            assertTrue(retrieved.isPresent());
            assertEquals("value", retrieved.get().definition());
        }
    }

    @Test
    void clear_removesAll() throws IOException {
        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(createConfig());
            storage.saveKeywordDefinition(KeywordDefinition.of("k", "d", "c", "doc1"));
            storage.clear();
            assertEquals(0, storage.size());
        }
    }

    @Test
    void getAllKeywords_returnsAll() throws IOException {
        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(createConfig());
            storage.saveKeywordDefinitions(List.of(
                KeywordDefinition.of("alpha", "d", "c", "doc1"),
                KeywordDefinition.of("beta", "d", "c", "doc2")
            ));
            var allKeywords = storage.getAllKeywords();
            assertEquals(2, allKeywords.size());
            assertTrue(allKeywords.contains("alpha"));
            assertTrue(allKeywords.contains("beta"));
        }
    }

    @Test
    void getStorageType_returnsLocalFile() {
        LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage();
        assertEquals("local-file", storage.getStorageType());
    }

    @Test
    void notInitialized_throwsException() {
        LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage();
        assertThrows(StorageException.class, () -> storage.saveKeywordDefinition(
            KeywordDefinition.of("k", "d", "c", "doc1")));
    }

    @Test
    void initialize_withoutFilePath_createsEmptyDict() throws IOException {
        try (LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage()) {
            storage.initialize(new DictionaryStorageConfig());
            assertTrue(storage.isHealthy());
            assertEquals(0, storage.size());
        }
    }
}
