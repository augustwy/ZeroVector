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

package cn.nexon.zerovector.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelTests {

    // --- Document tests ---

    @Test
    void document_constructsWithRequiredFields() {
        Document doc = new Document("id1", "Test Title", "content", null, null, Instant.now(), Map.of());
        assertEquals("id1", doc.id());
        assertEquals("Test Title", doc.title());
        assertEquals("content", doc.content());
    }

    @Test
    void document_rejectsNullId() {
        assertThrows(IllegalArgumentException.class,
            () -> new Document(null, "title", "content", null, null, Instant.now(), Map.of()));
    }

    @Test
    void document_rejectsEmptyId() {
        assertThrows(IllegalArgumentException.class,
            () -> new Document("", "title", "content", null, null, Instant.now(), Map.of()));
    }

    @Test
    void document_rejectsNullTitle() {
        assertThrows(IllegalArgumentException.class,
            () -> new Document("id1", null, "content", null, null, Instant.now(), Map.of()));
    }

    @Test
    void document_rejectsNullContentAndNullFilePath() {
        assertThrows(IllegalArgumentException.class,
            () -> new Document("id1", "title", null, null, null, Instant.now(), Map.of()));
    }

    @Test
    void document_acceptsFilePathWithoutContent() {
        Document doc = new Document("id1", "title", null, "/path/to/file", "md5hash", Instant.now(), Map.of());
        assertTrue(doc.isFileBased());
        assertEquals("/path/to/file", doc.filePath());
    }

    @Test
    void document_defaultsCreatedAtIfNull() {
        Document doc = new Document("id1", "title", "content", null, null, null, null);
        assertNotNull(doc.createdAt());
    }

    @Test
    void document_defaultsMetadataIfNull() {
        Document doc = new Document("id1", "title", "content", null, null, Instant.now(), null);
        assertNotNull(doc.metadata());
        assertTrue(doc.metadata().isEmpty());
    }

    @Test
    void document_fromContent_factory() {
        Document doc = Document.fromContent("id1", "title", "body", Map.of("key", "val"));
        assertEquals("id1", doc.id());
        assertEquals("body", doc.content());
        assertFalse(doc.isFileBased());
    }

    @Test
    void document_fromFile_factory() {
        Document doc = Document.fromFile("id1", "title", "/path", "md5", Map.of());
        assertNull(doc.content());
        assertTrue(doc.isFileBased());
    }

    // --- DocumentChunk tests ---

    @Test
    void documentChunk_withContent_createsContentBased() {
        DocumentChunk chunk = DocumentChunk.withContent("c1", "content body", "summary", Map.of());
        assertEquals("c1", chunk.id());
        assertEquals("content body", chunk.content());
        assertFalse(chunk.isFilePathBased());
        assertEquals("content body", chunk.getActualContent());
    }

    @Test
    void documentChunk_withFilePath_createsFileBased() {
        DocumentChunk chunk = DocumentChunk.withFilePath("c1", "summary", "/path/to/file", Map.of());
        assertEquals("c1", chunk.id());
        assertNull(chunk.content());
        assertTrue(chunk.isFilePathBased());
        assertNull(chunk.getActualContent());
    }

    @Test
    void documentChunk_defaultsMetadata() {
        DocumentChunk chunk = new DocumentChunk("c1", "content", "summary", null, null, null);
        assertNotNull(chunk.metadata());
        assertTrue(chunk.metadata().isEmpty());
    }

    // --- TreeNode tests ---

    @Test
    void treeNode_leafType_isLeaf() {
        TreeNode node = new TreeNode("n1", "leaf", "desc", NodeType.LEAF,
            List.of(), List.of("chunk1"), List.of(), List.of(), List.of());
        assertTrue(node.isLeaf());
        assertFalse(node.hasChildren());
        assertTrue(node.hasChunks());
    }

    @Test
    void treeNode_categoryType_hasChildren() {
        TreeNode node = new TreeNode("n1", "category", "desc", NodeType.CATEGORY,
            List.of("child1", "child2"), List.of(), List.of(), List.of(), List.of());
        assertFalse(node.isLeaf());
        assertTrue(node.hasChildren());
        assertFalse(node.hasChunks());
    }

    @Test
    void treeNode_defaultsEmptyLists() {
        TreeNode node = new TreeNode("n1", "name", "desc", NodeType.ROOT, null, null, null, null, null);
        assertTrue(node.childrenIds().isEmpty());
        assertTrue(node.chunkIds().isEmpty());
        assertTrue(node.keyEntities().isEmpty());
        assertTrue(node.keywords().isEmpty());
        assertTrue(node.exampleQuestions().isEmpty());
    }

    // --- KeywordDefinition tests ---

    @Test
    void keywordDefinition_constructs() {
        KeywordDefinition def = new KeywordDefinition("ZeroVector", "A knowledge base", "docs context", "doc1", 5);
        assertEquals("zerovector", def.normalizedKeyword());
        assertEquals("doc1", def.documentId());
    }

    @Test
    void keywordDefinition_defaultsFrequencyToOne() {
        KeywordDefinition def = new KeywordDefinition("key", "def", "ctx", "doc1", 0);
        assertEquals(1, def.frequency());
    }

    @Test
    void keywordDefinition_rejectsNullKeyword() {
        assertThrows(IllegalArgumentException.class,
            () -> new KeywordDefinition(null, "def", "ctx", "doc1", 1));
    }

    @Test
    void keywordDefinition_rejectsEmptyKeyword() {
        assertThrows(IllegalArgumentException.class,
            () -> new KeywordDefinition(" ", "def", "ctx", "doc1", 1));
    }

    @Test
    void keywordDefinition_rejectsNullDefinition() {
        assertThrows(IllegalArgumentException.class,
            () -> new KeywordDefinition("key", null, "ctx", "doc1", 1));
    }

    @Test
    void keywordDefinition_rejectsNullDocumentId() {
        assertThrows(IllegalArgumentException.class,
            () -> new KeywordDefinition("key", "def", "ctx", null, 1));
    }

    @Test
    void keywordDefinition_equality_byNormalizedKeywordAndDocument() {
        KeywordDefinition def1 = KeywordDefinition.of("ZeroVector", "A KB", "ctx", "doc1");
        KeywordDefinition def2 = KeywordDefinition.of("zerovector", "A KB", "ctx", "doc1");
        KeywordDefinition def3 = KeywordDefinition.of("ZeroVector", "A KB", "ctx", "doc2");
        assertEquals(def1, def2);
        assertNotEquals(def1, def3);
    }

    // --- SemanticTree tests ---

    @Test
    void semanticTree_empty_returnsNullRoot() {
        SemanticTree tree = SemanticTree.empty();
        assertNull(tree.rootNode());
        assertTrue(tree.nodes().isEmpty());
        assertTrue(tree.chunks().isEmpty());
    }

    @Test
    void semanticTree_getNode_returnsNode() {
        TreeNode node = new TreeNode("n1", "root", "desc", NodeType.ROOT,
            List.of(), List.of(), List.of(), List.of(), List.of());
        SemanticTree tree = new SemanticTree(node, Map.of("n1", node), Map.of());
        assertEquals(node, tree.getNode("n1"));
        assertNull(tree.getNode("nonexistent"));
    }

    @Test
    void semanticTree_defaultsNullMaps() {
        SemanticTree tree = new SemanticTree(null, null, null);
        assertTrue(tree.nodes().isEmpty());
        assertTrue(tree.chunks().isEmpty());
    }

    // --- NavigationResult tests ---

    @Test
    void navigationResult_defaultsUsageStats() {
        NavigationResult result = NavigationResult.of(List.of(), "reasoning", List.of());
        assertNotNull(result.llmUsageStats());
    }

    // --- NavigationPath tests ---

    @Test
    void navigationPath_defaultsEmptyCollections() {
        NavigationPath path = new NavigationPath("query", null, null, null);
        assertTrue(path.visitedNodes().isEmpty());
        assertEquals("", path.reasoning());
        assertTrue(path.documents().isEmpty());
    }

    @Test
    void navigationPath_legacyConstructor() {
        NavigationPath path = new NavigationPath("query", List.of("n1"), "reasoning");
        assertEquals("query", path.query());
        assertEquals(List.of("n1"), path.visitedNodes());
        assertEquals("reasoning", path.reasoning());
    }

    // --- NodeType tests ---

    @Test
    void nodeType_hasThreeValues() {
        assertEquals(3, NodeType.values().length);
        assertTrue(java.util.Arrays.asList(NodeType.values()).containsAll(
            List.of(NodeType.ROOT, NodeType.CATEGORY, NodeType.LEAF)));
    }
}
