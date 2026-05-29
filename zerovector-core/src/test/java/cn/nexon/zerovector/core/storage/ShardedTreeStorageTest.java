package cn.nexon.zerovector.core.storage;

import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.NodeType;
import cn.nexon.zerovector.core.model.SemanticTree;
import cn.nexon.zerovector.core.model.TreeNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShardedTreeStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void constructor_createsDirectory() throws IOException {
        String dir = tempDir.resolve("shards").toString();
        try (ShardedTreeStorage storage = new ShardedTreeStorage(dir, 10)) {
            assertTrue(Files.exists(Path.of(dir)));
        }
    }

    @Test
    void saveAndLoadTree_roundtrip() throws IOException {
        String dir = tempDir.resolve("roundtrip").toString();
        TreeNode root = new TreeNode("root", "Root", "Root node", NodeType.ROOT,
            List.of("child1"), List.of(), List.of("entity1"), List.of("kw1"), List.of("q1"));
        TreeNode child = new TreeNode("child1", "Child", "Child leaf", NodeType.LEAF,
            List.of(), List.of("chunk1"), List.of(), List.of(), List.of());
        DocumentChunk chunk = DocumentChunk.withContent("chunk1", "chunk content", "summary", Map.of());
        SemanticTree tree = new SemanticTree(root, Map.of("root", root, "child1", child), Map.of("chunk1", chunk));

        try (ShardedTreeStorage storage = new ShardedTreeStorage(dir, 10)) {
            storage.saveTree(tree);
        }

        // Load fresh
        try (ShardedTreeStorage storage = new ShardedTreeStorage(dir, 10)) {
            SemanticTree loaded = storage.loadTree();
            assertNotNull(loaded);
            assertNotNull(loaded.rootNode());
            assertEquals("Root", loaded.rootNode().name());
            assertEquals("Root node", loaded.rootNode().description());

            TreeNode loadedChild = loaded.getNode("child1");
            assertNotNull(loadedChild);
            assertEquals("Child", loadedChild.name());

            DocumentChunk loadedChunk = loaded.getChunk("chunk1");
            assertNotNull(loadedChunk);
            assertEquals("chunk content", loadedChunk.content());
        }
    }

    @Test
    void loadTree_noMetadata_returnsNull() throws IOException {
        String dir = tempDir.resolve("nometa").toString();
        try (ShardedTreeStorage storage = new ShardedTreeStorage(dir, 10)) {
            SemanticTree loaded = storage.loadTree();
            assertNull(loaded);
        }
    }

    @Test
    void updateTreeIncremental_addsNewData() throws IOException {
        String dir = tempDir.resolve("incremental").toString();
        TreeNode root = new TreeNode("root", "Root", "Root", NodeType.ROOT,
            List.of(), List.of(), List.of(), List.of(), List.of());
        SemanticTree oldTree = new SemanticTree(root, Map.of("root", root), Map.of());

        TreeNode newChild = new TreeNode("child1", "Child", "Added later", NodeType.LEAF,
            List.of(), List.of("chunk1"), List.of(), List.of(), List.of());
        DocumentChunk chunk = DocumentChunk.withContent("chunk1", "new content", "summary", Map.of());
        SemanticTree newTree = new SemanticTree(root,
            Map.of("root", root, "child1", newChild),
            Map.of("chunk1", chunk));

        try (ShardedTreeStorage storage = new ShardedTreeStorage(dir, 10)) {
            storage.saveTree(oldTree);
            storage.updateTreeIncremental(oldTree, newTree);
        }

        try (ShardedTreeStorage storage = new ShardedTreeStorage(dir, 10)) {
            SemanticTree loaded = storage.loadTree();
            assertNotNull(loaded);
            assertNotNull(loaded.getNode("child1"));
            assertEquals("Child", loaded.getNode("child1").name());
        }
    }

    @Test
    void saveTree_withSharding_createsMultipleFiles() throws IOException {
        String dir = tempDir.resolve("multipleshards").toString();
        int shardSize = 2;

        // Create enough nodes to trigger multiple shard files
        Map<String, TreeNode> nodes = new java.util.HashMap<>();
        for (int i = 0; i < 5; i++) {
            String id = "node" + i;
            nodes.put(id, new TreeNode(id, "Node" + i, "Desc" + i, NodeType.LEAF,
                List.of(), List.of("chunk" + i), List.of(), List.of(), List.of()));
        }
        Map<String, DocumentChunk> chunks = new java.util.HashMap<>();
        for (int i = 0; i < 5; i++) {
            chunks.put("chunk" + i, DocumentChunk.withContent("chunk" + i, "content" + i, "sum", Map.of()));
        }

        TreeNode root = new TreeNode("root", "Root", "Root", NodeType.ROOT,
            List.of("node0", "node1", "node2", "node3", "node4"), List.of(), List.of(), List.of(), List.of());
        nodes.put("root", root);
        SemanticTree tree = new SemanticTree(root, nodes, chunks);

        try (ShardedTreeStorage storage = new ShardedTreeStorage(dir, shardSize)) {
            storage.saveTree(tree);
        }

        // Should have multiple shard files
        try (var files = Files.list(Path.of(dir))) {
            long nodeShardFiles = files
                .filter(p -> p.getFileName().toString().startsWith("node_"))
                .count();
            // 6 nodes (root + 5 children) / shardSize 2 = 3 shards
            assertTrue(nodeShardFiles >= 2, "Expected at least 2 node shard files, got " + nodeShardFiles);
        }
    }

    @Test
    void lazyLoad_nodeViaGetNode() throws IOException {
        String dir = tempDir.resolve("lazyload").toString();
        TreeNode root = new TreeNode("root", "Root", "Root", NodeType.ROOT,
            List.of("leaf1"), List.of(), List.of(), List.of(), List.of());
        TreeNode leaf = new TreeNode("leaf1", "Leaf", "A leaf", NodeType.LEAF,
            List.of(), List.of("chunk1"), List.of(), List.of(), List.of());
        DocumentChunk chunk = DocumentChunk.withContent("chunk1", "lazy content", "sum", Map.of());
        SemanticTree tree = new SemanticTree(root, Map.of("root", root, "leaf1", leaf), Map.of("chunk1", chunk));

        try (ShardedTreeStorage storage = new ShardedTreeStorage(dir, 10)) {
            storage.saveTree(tree);
        }

        try (ShardedTreeStorage storage = new ShardedTreeStorage(dir, 10)) {
            // Don't call loadTree() — test lazy loading via getNode directly
            // First, load metadata by calling loadTree()
            SemanticTree loaded = storage.loadTree();
            assertNotNull(loaded);

            // Now nodes should be accessible
            TreeNode fetchedLeaf = storage.getNode("leaf1");
            assertNotNull(fetchedLeaf);
            assertEquals("Leaf", fetchedLeaf.name());

            DocumentChunk fetchedChunk = storage.getChunk("chunk1");
            assertNotNull(fetchedChunk);
            assertEquals("lazy content", fetchedChunk.content());
        }
    }

    @Test
    void getNode_nonexistent_returnsNull() throws IOException {
        String dir = tempDir.resolve("nonexistentnode").toString();
        try (ShardedTreeStorage storage = new ShardedTreeStorage(dir, 10)) {
            assertNull(storage.getNode("no_such_node"));
        }
    }

    @Test
    void getChunk_nonexistent_returnsNull() throws IOException {
        String dir = tempDir.resolve("nonexistentchunk").toString();
        try (ShardedTreeStorage storage = new ShardedTreeStorage(dir, 10)) {
            assertNull(storage.getChunk("no_such_chunk"));
        }
    }

    @Test
    void shardIndices_areAccessible() throws IOException {
        String dir = tempDir.resolve("indices").toString();
        TreeNode root = new TreeNode("root", "Root", "Root", NodeType.ROOT,
            List.of(), List.of(), List.of(), List.of(), List.of());
        SemanticTree tree = new SemanticTree(root, Map.of("root", root), Map.of());

        try (ShardedTreeStorage storage = new ShardedTreeStorage(dir, 10)) {
            storage.saveTree(tree);
            assertFalse(storage.getNodeShardIndex().isEmpty());
            assertTrue(storage.getNodeShardIndex().containsKey("root"));
            assertTrue(storage.getChunkShardIndex().isEmpty()); // no chunks
        }
    }
}
