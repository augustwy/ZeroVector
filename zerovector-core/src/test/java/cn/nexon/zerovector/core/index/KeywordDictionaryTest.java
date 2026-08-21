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

package cn.nexon.zerovector.core.index;

import cn.nexon.zerovector.core.model.KeywordDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class KeywordDictionaryTest {

    @TempDir
    Path tempDir;

    @Test
    void addKeywordDefinition_storesDefinition() {
        KeywordDictionary dict = new KeywordDictionary();
        KeywordDefinition def = KeywordDefinition.of("ZeroVector", "A knowledge base", "context", "doc1");
        dict.addKeywordDefinition(def);

        assertTrue(dict.containsKeyword("ZeroVector"));
        assertEquals(1, dict.size());
    }

    @Test
    void addKeywordDefinitions_batch() {
        KeywordDictionary dict = new KeywordDictionary();
        dict.addKeywordDefinitions(List.of(
            KeywordDefinition.of("k1", "d1", "c1", "doc1"),
            KeywordDefinition.of("k2", "d2", "c2", "doc2")
        ));
        assertEquals(2, dict.size());
    }

    @Test
    void addEntry_linksKeywordToNode() {
        KeywordDictionary dict = new KeywordDictionary();
        dict.addEntry("java", "node1", 1.0);

        List<String> nodes = dict.getNodesForKeyword("java");
        assertEquals(1, nodes.size());
        assertTrue(nodes.contains("node1"));
    }

    @Test
    void addEntry_normalizesKeyword() {
        KeywordDictionary dict = new KeywordDictionary();
        dict.addEntry("JAVA", "node1", 1.0);

        List<String> nodes = dict.getNodesForKeyword("java");
        assertEquals(1, nodes.size());
    }

    @Test
    void addEntries_batch() {
        KeywordDictionary dict = new KeywordDictionary();
        dict.addEntries(List.of("java", "python", "rust"), "node1");

        assertEquals(1, dict.getNodesForKeyword("java").size());
        assertEquals(1, dict.getNodesForKeyword("python").size());
        assertEquals(1, dict.getNodesForKeyword("rust").size());
    }

    @Test
    void addEntriesWithDefinitions() {
        KeywordDictionary dict = new KeywordDictionary();
        List<KeywordDefinition> defs = List.of(
            KeywordDefinition.of("java", "lang", "ctx", "doc1"),
            KeywordDefinition.of("python", "lang", "ctx", "doc1")
        );
        dict.addEntriesWithDefinitions(defs, "node1");

        assertTrue(dict.containsKeyword("java"));
        assertTrue(dict.containsKeyword("python"));
        assertEquals(2, dict.getAllKeywords().size());
    }

    @Test
    void getKeywordDefinition_returnsCorrectDef() {
        KeywordDictionary dict = new KeywordDictionary();
        KeywordDefinition def = KeywordDefinition.of("test", "a test keyword", "test context", "doc1");
        dict.addKeywordDefinition(def);

        Optional<KeywordDefinition> retrieved = dict.getKeywordDefinition("test");
        assertTrue(retrieved.isPresent());
        assertEquals("a test keyword", retrieved.get().definition());
    }

    @Test
    void getKeywordDefinition_nonexistent_returnsEmpty() {
        KeywordDictionary dict = new KeywordDictionary();
        assertTrue(dict.getKeywordDefinition("nonexistent").isEmpty());
    }

    @Test
    void matchCandidates_returnsWeightedNodes() {
        KeywordDictionary dict = new KeywordDictionary();
        dict.addEntry("zerovector", "node1", 1.0);
        dict.addEntry("knowledge", "node2", 2.0);
        dict.addEntry("base", "node1", 0.5);

        Map<String, Double> candidates = dict.matchCandidates("zerovector knowledge base");
        assertEquals(2, candidates.size());
        assertTrue(candidates.containsKey("node1"));
        assertTrue(candidates.containsKey("node2"));
    }

    @Test
    void matchCandidatesFromKeywords() {
        KeywordDictionary dict = new KeywordDictionary();
        dict.addEntry("java", "node1", 1.0);
        dict.addEntry("python", "node2", 1.0);

        Map<String, Double> candidates = dict.matchCandidatesFromKeywords(List.of("java"));
        assertEquals(1, candidates.size());
        assertTrue(candidates.containsKey("node1"));
    }

    @Test
    void clear_removesAll() {
        KeywordDictionary dict = new KeywordDictionary();
        dict.addKeywordDefinition(KeywordDefinition.of("k", "d", "c", "doc1"));
        dict.addEntry("k", "n1", 1.0);
        dict.clear();

        assertEquals(0, dict.size());
        assertFalse(dict.containsKeyword("k"));
        assertTrue(dict.getNodesForKeyword("k").isEmpty());
    }

    @Test
    void removeKeywordDefinition() {
        KeywordDictionary dict = new KeywordDictionary();
        dict.addKeywordDefinition(KeywordDefinition.of("remove_me", "d", "c", "doc1"));
        dict.addEntry("remove_me", "n1", 1.0);

        dict.removeKeywordDefinition("remove_me");
        assertFalse(dict.containsKeyword("remove_me"));
        assertTrue(dict.getNodesForKeyword("remove_me").isEmpty());
    }

    @Test
    void getAllKeywordDefinitions() {
        KeywordDictionary dict = new KeywordDictionary();
        KeywordDefinition def1 = KeywordDefinition.of("k1", "d1", "c1", "doc1");
        KeywordDefinition def2 = KeywordDefinition.of("k2", "d2", "c2", "doc2");
        dict.addKeywordDefinitions(List.of(def1, def2));

        var all = dict.getAllKeywordDefinitions();
        assertEquals(2, all.size());
    }

    @Test
    void saveAndLoadToFile_persistsData() throws IOException {
        Path dictFile = tempDir.resolve("dict.json");

        KeywordDictionary dict = new KeywordDictionary();
        dict.addKeywordDefinition(KeywordDefinition.of("persist", "value", "context", "doc1"));
        dict.addEntry("persist", "node1", 2.0);
        dict.saveToFile(dictFile.toString());

        // Load fresh
        KeywordDictionary loaded = KeywordDictionary.loadFromFile(dictFile.toString());
        assertTrue(loaded.containsKeyword("persist"));
        assertEquals(1, loaded.size());
        assertEquals(1, loaded.getNodesForKeyword("persist").size());
    }

    @Test
    void loadFromFile_nonexistent_returnsEmptyDict() throws IOException {
        KeywordDictionary dict = KeywordDictionary.loadFromFile(
            tempDir.resolve("nonexistent.json").toString());
        assertNotNull(dict);
        assertEquals(0, dict.size());
    }

    @Test
    void getNodesForKeyword_nonexistent_returnsEmptyList() {
        KeywordDictionary dict = new KeywordDictionary();
        assertTrue(dict.getNodesForKeyword("nothing").isEmpty());
    }

    @Test
    void getAllKeywords_returnsAll() {
        KeywordDictionary dict = new KeywordDictionary();
        dict.addKeywordDefinitions(List.of(
            KeywordDefinition.of("alpha", "d", "c", "doc1"),
            KeywordDefinition.of("beta", "d", "c", "doc2")
        ));
        assertEquals(2, dict.getAllKeywords().size());
    }

    @Test
    void addEntry_accumulatesWeights() {
        KeywordDictionary dict = new KeywordDictionary();
        dict.addEntry("key", "node1", 1.0);
        dict.addEntry("key", "node1", 2.0);

        // weight should be 3.0 (1 + 2)
        Map<String, Double> candidates = dict.matchCandidatesFromKeywords(List.of("key"));
        assertEquals(3.0, candidates.get("node1"), 0.001);
    }
}
